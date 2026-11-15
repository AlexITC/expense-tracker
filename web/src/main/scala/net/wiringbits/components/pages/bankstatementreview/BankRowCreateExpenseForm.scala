package net.wiringbits.components.pages.bankstatementreview

import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.components.pages.{AutocompleteLabels, CategoryResolver, MerchantResolver}

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.{AiSuggestions, CreateExpenseFromRow, GetBankStatement}
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.{CreateImportRule, GetMerchants, ImportRuleAction}
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}
import net.wiringbits.core.SafeHooks
import net.wiringbits.forms.BankRowExpenseFormData
import net.wiringbits.ui.components.inputs.{
  AmountInput,
  CategoryAutocompleteInput,
  DateInput,
  OptionalMerchantAutocompleteInput
}
import net.wiringbits.ui.forms.{ExistingOrNewValue, ExpLocalDate, PositiveBigDecimal, SubmitHooks}
import net.wiringbits.utils.ReceiptFileViewer
import net.wiringbits.webapp.common.validators.ValidationResult
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, label, onSubmit}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object BankRowCreateExpenseForm {
  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(
      stmtId: UUID,
      row: GetBankStatement.StatementRowData,
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category],
      ruleList: List[ImportRuleMatcher.Rule],
      aiSuggestion: Option[AiSuggestions.RowSuggestion],
      ctx: AppContext,
      onSuccess: UUID => Unit,
      onCancel: () => Unit,
      onNewEntities: (Option[ImportRuleMatcher.Rule], Option[GetMerchants.Response.Merchant]) => Unit
  )

  /** Initial (merchant, categoryId) for the form: a SetMerchant rule winner first (explicit rules plus merchant names,
    * see `ImportRuleMatcher.effectiveRules`) with its override category, else the merchant's default category, then a
    * SetCategory rule winner's category (no merchant, AI not consulted), then the AI suggestion. Ids that are not among
    * the user's merchants/categories are dropped.
    */
  def initialSelection(
      row: GetBankStatement.StatementRowData,
      ruleList: List[ImportRuleMatcher.Rule],
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category],
      aiSuggestion: Option[AiSuggestions.RowSuggestion]
  ): (Option[GetMerchants.Response.Merchant], Option[UUID]) = {
    val verdict = rowVerdict(row, ruleList, merchants)
    val ruleMerchant = RowRuleVerdict.merchantId(verdict).flatMap(mid => merchants.find(_.id == mid))

    (ruleMerchant, verdict) match {
      case (Some(m), _) =>
        (Some(m), RowRuleVerdict.categoryFor(verdict, m.categoryId).filter(cid => categories.exists(_.id == cid)))
      case (None, RowRuleVerdict.SetCategory(categoryId, _)) =>
        (None, Option.when(categories.exists(_.id == categoryId))(categoryId))
      case (None, _) =>
        aiSuggestion match {
          case Some(s) =>
            (
              s.merchantId.flatMap(mid => merchants.find(_.id == mid)),
              s.categoryId.filter(cid => categories.exists(_.id == cid))
            )
          case None => (None, None)
        }
    }
  }

  /** The row's verdict under the effective rules (explicit rules plus merchant names). */
  def rowVerdict(
      row: GetBankStatement.StatementRowData,
      ruleList: List[ImportRuleMatcher.Rule],
      merchants: List[GetMerchants.Response.Merchant]
  ): RowRuleVerdict.Verdict =
    RowRuleVerdict.verdict(
      ImportRuleMatcher.effectiveRules(ruleList, merchants.map(m => m.id -> m.name)),
      row.description,
      row.amount
    )

  /** The merchant text the form starts with when no merchant is prefilled: the row description, except for a row a
    * SetCategory rule handles, which starts blank so saving does not create a merchant named after the description.
    */
  def initialMerchantInput(row: GetBankStatement.StatementRowData, verdict: RowRuleVerdict.Verdict): String =
    verdict match {
      case RowRuleVerdict.SetCategory(_, _) => ""
      case _ => row.description
    }

  /** The notes the form starts with: the rule description, else the row description. */
  def initialNotes(row: GetBankStatement.StatementRowData, verdict: RowRuleVerdict.Verdict): String =
    RowRuleVerdict.notes(verdict, row.description)

  enum RuleHint {
    case CoveredByRule(pattern: String)
    case MatchesByName
    case NeedsAlias
  }

  /** How future rows like this one would be matched: by an explicit rule, by the merchant name itself (an implicit
    * rule), or not at all, in which case the form offers to create an alias rule.
    */
  def ruleHint(
      row: GetBankStatement.StatementRowData,
      explicitRules: List[ImportRuleMatcher.Rule],
      merchantName: String
  ): RuleHint =
    ImportRuleMatcher.findRowWinner(explicitRules, row.description, row.amount) match {
      case Some(rule) => RuleHint.CoveredByRule(rule.pattern)
      case None =>
        val nameRules = ImportRuleMatcher.effectiveRules(Nil, List(new UUID(0L, 0L) -> merchantName))
        if (ImportRuleMatcher.findMatches(nameRules, row.description, Some(row.amount)).nonEmpty) RuleHint.MatchesByName
        else RuleHint.NeedsAlias
    }

  /** The alias pattern worth storing: blank means no rule, an alias too short to ever match is useless, and an alias
    * equal to the merchant name (once normalized) is redundant because merchant names already match implicitly.
    */
  def aliasToCreate(pattern: String, merchantName: String): Option[String] = {
    val trimmed = pattern.trim
    val normalized = ImportRuleMatcher.normalize(trimmed)
    Option.when(
      normalized.length >= ImportRuleMatcher.MinPatternLength && normalized != ImportRuleMatcher.normalize(merchantName)
    )(trimmed)
  }

  /** The SetCategory rule pattern to store after confirming a row with a category but no merchant: only when the user
    * opted in and no explicit rule already covers the row, subject to the same length check as merchant aliases.
    */
  def categoryRuleToCreate(
      hint: RuleHint,
      merchantName: String,
      categoryIdOpt: Option[UUID],
      optIn: Boolean,
      pattern: String
  ): Option[String] =
    if (optIn && merchantName.isEmpty && categoryIdOpt.isDefined && hint == RuleHint.NeedsAlias)
      aliasToCreate(pattern = pattern, merchantName = "")
    else None

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (initMerchant, initCategoryIdOpt) =
      initialSelection(props.row, props.ruleList, props.merchants, props.categories, props.aiSuggestion)
    val initCategory = initCategoryIdOpt.flatMap(cid => props.categories.find(_.id == cid))

    val (formData, setFormData) = Hooks.useState {
      val initVerdict = rowVerdict(props.row, props.ruleList, props.merchants)
      BankRowExpenseFormData.initial(
        row = props.row,
        initialMerchant = initMerchant,
        merchantInput = initialMerchantInput(props.row, initVerdict),
        initialCategory = initCategory,
        notes = initialNotes(props.row, initVerdict)
      )
    }
    val (receipts, setReceipts) = Hooks.useState[List[GetReceipts.Response.Receipt]](Nil)
    // Receipts whose merchant offer the user already accepted or declined.
    val (decidedReceiptIds, setDecidedReceiptIds) = Hooks.useState[Set[UUID]](Set.empty)
    val submit = SubmitHooks.useSubmit()
    // Set once the expense exists but its alias rule failed: the form stays open only to show that error.
    val (savedExpenseId, setSavedExpenseId) = Hooks.useState[Option[UUID]](None)

    val merchantIdOpt = formData.merchantIdOpt
    val merchantName =
      formData.merchant.valueOpt.flatten.map(_.value.string).getOrElse(formData.merchant.inputValue.trim)
    val categoryIdOpt = formData.category.valueOpt.flatMap(_.id)
    val categoryInput = formData.category.inputValue.trim
    val hint = ruleHint(props.row, props.ruleList, merchantName)
    val showCategoryRuleOption = merchantName.isEmpty && categoryInput.nonEmpty && hint == RuleHint.NeedsAlias

    // A suggestion can arrive (via polling) after the form was opened blank; apply it only while the
    // merchant/category fields are still untouched so user edits are never overwritten.
    val aiSuggestionKey = props.aiSuggestion.map(s => s"${s.merchantId}|${s.categoryId}").getOrElse("")
    SafeHooks.useEffect(aiSuggestionKey)(() => {
      setFormData(cur => if (cur.isPristine(props.row)) cur.applySuggestion(initMerchant, initCategory) else cur)
    })

    SafeHooks.useEffect()(() => {
      props.ctx.api.client.getReceipts.onComplete {
        case Success(resp) => setReceipts(resp.data.filter(r => r.expenseId.isEmpty && r.amount.isDefined))
        case Failure(_) => ()
      }
      ()
    })

    def rankReceipts(receipts: List[GetReceipts.Response.Receipt]): List[(GetReceipts.Response.Receipt, Int)] = {
      val rowDesc = props.row.description.toLowerCase
      receipts
        .flatMap { r =>
          (r.amount, r.date) match {
            case (Some(rAmt), Some(rDate))
                if BankStatementReviewLogic.rowMatchesCandidate(
                  rowAmount = props.row.amount,
                  rowDate = props.row.date,
                  candidateAmount = rAmt,
                  candidateDate = rDate
                ) =>
              val merchMatch = merchantIdOpt
                .flatMap(id => props.merchants.find(_.id == id))
                .map(_.name) match {
                case Some(mn)
                    if r.merchantName
                      .exists(rn => rn.toLowerCase.contains(mn.toLowerCase) || rowDesc.contains(rn.toLowerCase)) =>
                  1
                case _ => 0
              }
              Some((r, rAmt, rDate, merchMatch))
            case _ => None
          }
        }
        .sortBy { case (_, rAmt, rDate, merch) =>
          (-merch, (rAmt.abs - props.row.amount.abs).abs, Math.abs(props.row.date.toEpochDay - rDate.toEpochDay))
        }
        .map { case (r, _, _, merch) => (r, merch) }
    }

    def handleSubmit(): Unit =
      formData.submitRequest match {
        case None =>
          submit.fail(formData.formValidationErrors.headOption.getOrElse("Please fix the highlighted fields"))
        case Some(resolved) =>
          submit.begin()

          val fExpense: Future[(UUID, Option[UUID], UUID)] = for {
            resolvedCategoryId <- CategoryResolver
              .findOrCreateCategory(props.ctx.api.client, props.categories, resolved.category)
            merchantResult <- MerchantResolver.findOrCreateMerchantOpt(
              props.ctx.api.client,
              props.merchants,
              resolved.merchant,
              Some(resolvedCategoryId)
            )
            resolvedMerchantId = merchantResult.map(_._1)
            _ = merchantResult.flatMap(_._2).foreach { m =>
              props.onNewEntities(None, Some(m))
              // A retry after a later failure must reuse this merchant instead of creating another one.
              setFormData(_.withResolvedMerchant(m.id))
            }
            req = CreateExpenseFromRow.Request(
              amount = resolved.amount,
              date = resolved.date,
              time = None,
              merchantId = resolvedMerchantId,
              notes = resolved.notes,
              splits = List(CreateExpenseFromRow.Split(resolvedCategoryId, resolved.amount)),
              receiptId = resolved.receiptId
            )
            resp <- props.ctx.api.client.createExpenseFromRow(bankStatementId = props.stmtId, rowId = props.row.id, req)
          } yield (resp.expenseId, resolvedMerchantId, resolvedCategoryId)

          fExpense.onComplete {
            case Failure(ex) => submit.fail(ex.getMessage)
            case Success((expenseId, resolvedMerchantId, resolvedCategoryId)) =>
              val aliasOpt =
                if (hint == RuleHint.NeedsAlias)
                  aliasToCreate(pattern = resolved.rulePattern, merchantName = merchantName)
                else None
              val categoryRuleOpt =
                categoryRuleToCreate(
                  hint,
                  merchantName = merchantName,
                  Some(resolvedCategoryId),
                  resolved.categoryRuleOptIn,
                  pattern = resolved.rulePattern
                )
              val ruleRequestOpt = (resolvedMerchantId, aliasOpt, categoryRuleOpt) match {
                case (Some(mid), Some(alias), _) =>
                  Some(
                    CreateImportRule.Request(
                      action = ImportRuleAction.SetMerchant(mid, categoryId = None, description = None),
                      pattern = alias,
                      amount = None
                    )
                  )
                case (None, _, Some(pattern)) =>
                  Some(
                    CreateImportRule.Request(
                      action = ImportRuleAction.SetCategory(resolvedCategoryId, description = None),
                      pattern = pattern,
                      amount = None
                    )
                  )
                case _ => None
              }
              ruleRequestOpt match {
                case Some(ruleRequest) =>
                  props.ctx.api.client
                    .createImportRule(ruleRequest)
                    .onComplete {
                      case Success(r) =>
                        props.onNewEntities(
                          Some(
                            ImportRuleMatcher.Rule(
                              r.id,
                              ruleRequest.action,
                              ruleRequest.pattern,
                              ruleRequest.amount
                            )
                          ),
                          None
                        )
                        props.onSuccess(expenseId)
                      case Failure(ex) =>
                        setSavedExpenseId(Some(expenseId))
                        submit.fail(s"Expense created, but the rule could not be created: ${ex.getMessage}")
                    }
                case None => props.onSuccess(expenseId)
              }
          }
      }

    def updateAmount(value: ValidationResult[PositiveBigDecimal]): Unit =
      setFormData(cur => cur.copy(amount = cur.amount.updated(value)))

    def updateDate(value: ValidationResult[ExpLocalDate]): Unit =
      setFormData(cur => cur.copy(date = cur.date.updated(value)))

    def updateMerchant(value: ValidationResult[Option[ExistingOrNewValue[MerchantName, UUID]]]): Unit =
      setFormData(cur => cur.selectMerchant(value, props.merchants, props.categories))

    def updateCategory(value: ValidationResult[ExistingOrNewValue[CategoryName, UUID]]): Unit =
      setFormData(cur => cur.copy(category = cur.category.updated(value)))

    def updateRulePattern(text: String): Unit =
      setFormData(cur => cur.copy(rulePattern = text))

    val rankedReceipts = rankReceipts(receipts)
    val receiptSuggestStyling = new CSSPropertiesUtils { fontSize = "0.8em"; color = "#555" }
    val receiptSelectedStyling = new CSSPropertiesUtils {
      fontSize = "0.8em"; color = "var(--lc-link)"; fontWeight = "bold"
    }
    val offer = BankStatementReviewLogic.receiptOffer(
      receipts.find(r => formData.receiptId.contains(r.id)),
      merchantIdOpt,
      props.merchants,
      decidedReceiptIds
    )
    val hintStyling = new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text-2)" }

    form(onSubmit := { e =>
      e.preventDefault()
      if (savedExpenseId.isEmpty) handleSubmit()
    })(
      mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "12px" })(
        mui
          .Typography("New expense")
          .sx(new CSSPropertiesUtils { fontWeight = "700"; fontSize = "14px"; userSelect = "none" }),
        submit.error match {
          case Some(msg) =>
            mui.Typography(msg).sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px" })
          case None => Fragment()
        },
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "12px" })(
          mui.Box.sx(new CSSPropertiesUtils { flex = "1" })(
            AmountInput.component(
              AmountInput.Props(field = formData.amount, disabled = submit.isSubmitting, onChange = updateAmount)
            )
          ),
          mui.Box.sx(new CSSPropertiesUtils { flex = "1" })(
            DateInput.component(
              DateInput.Props(field = formData.date, disabled = submit.isSubmitting, onChange = updateDate)
            )
          )
        ),
        mui.TextField
          .outlined()
          .label("Notes")
          .size("small")
          .value(formData.notes)
          .disabled(submit.isSubmitting)
          .onChange { e =>
            val text = e.target.asInstanceOf[dom.HTMLInputElement].value
            setFormData(cur => cur.copy(notes = text))
          },
        (OptionalMerchantAutocompleteInput
          .component(
            OptionalMerchantAutocompleteInput.Props(
              field = formData.merchant,
              options = props.merchants,
              label = "Merchant",
              onChange = updateMerchant,
              disabled = submit.isSubmitting
            )
          ): ReactElement),
        (CategoryAutocompleteInput
          .component(
            CategoryAutocompleteInput.Props(
              field = formData.category,
              options = props.categories,
              label = "Category",
              onChange = updateCategory,
              disabled = submit.isSubmitting
            )
          ): ReactElement),
        AutocompleteLabels
          .categoryDescription(props.categories, categoryIdOpt)
          .map(d => mui.Typography(d).sx(hintStyling): ReactElement)
          .getOrElse(Fragment()),
        if (merchantName.isEmpty) Fragment()
        else
          hint match {
            case RuleHint.CoveredByRule(pattern) =>
              mui.Typography(s"Covered by rule: $pattern").sx(hintStyling)
            case RuleHint.MatchesByName =>
              mui
                .Typography(s"Future rows matching \"$merchantName\" are matched automatically")
                .sx(hintStyling)
            case RuleHint.NeedsAlias =>
              mui.TextField
                .outlined()
                .label("Rule pattern")
                .size("small")
                .value(formData.rulePattern)
                .helperText("Future rows containing this text get this merchant. Leave blank for no rule.")
                .onChange(e => updateRulePattern(e.target.asInstanceOf[dom.HTMLInputElement].value))
          },
        if (showCategoryRuleOption)
          mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "8px" })(
            label(
              mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; cursor = "pointer" })(
                mui.Checkbox
                  .checked(formData.categoryRuleOptIn)
                  .size("small")
                  .onChange((_, checked) => setFormData(cur => cur.copy(categoryRuleOptIn = checked))),
                mui.Typography(s"Also categorize similar rows as $categoryInput").sx(hintStyling)
              )
            ),
            if (formData.categoryRuleOptIn)
              mui.TextField
                .outlined()
                .label("Rule pattern")
                .size("small")
                .value(formData.rulePattern)
                .helperText("Future rows containing this text get this category.")
                .onChange(e => updateRulePattern(e.target.asInstanceOf[dom.HTMLInputElement].value))
            else Fragment()
          )
        else Fragment(),
        if (rankedReceipts.nonEmpty)
          mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "6px" })(
            mui.Typography("Attach receipt (optional):").sx(receiptSuggestStyling),
            Fragment(
              rankedReceipts.take(5).map { case (r, _) =>
                val isSelected = formData.receiptId.contains(r.id)
                val label =
                  s"${r.documentFilename} | ${r.amount.getOrElse("")} | ${r.date.getOrElse("")} | ${r.merchantName
                      .getOrElse("-")}"
                (mui.Box
                  .sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "8px" })(
                    mui.Typography(label).sx(if (isSelected) receiptSelectedStyling else receiptSuggestStyling),
                    if (isSelected)
                      mui.Button
                        .normal()("Deselect")
                        .size("small")
                        .onClick(_ => setFormData(cur => cur.copy(receiptId = None)))
                    else
                      mui.Button
                        .normal()("Attach")
                        .size("small")
                        .onClick(_ => setFormData(cur => cur.copy(receiptId = Some(r.id)))),
                    mui.Button
                      .normal()("View")
                      .size("small")
                      .onClick(_ =>
                        ReceiptFileViewer.openInNewTab(props.ctx.api.client, r.id, r.documentFilename, submit.fail)
                      )
                  )
                  .withKey(r.id.toString): ReactElement)
              }*
            )
          )
        else Fragment(),
        offer match {
          case Some(o) =>
            val categoryLabel = o.categoryId
              .flatMap(cid => props.categories.find(_.id == cid))
              .map(_.name)
              .getOrElse("no default category")
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "8px" })(
              mui.Typography(s"Receipt says ${o.merchant.name} ($categoryLabel)").sx(hintStyling),
              mui.Button
                .normal()("Use receipt")
                .size("small")
                .onClick { _ =>
                  setFormData(cur => cur.useReceiptMerchant(o.merchant, o.categoryId, props.categories))
                  setDecidedReceiptIds(decidedReceiptIds + o.receiptId)
                },
              mui.Button
                .normal()("Keep suggestion")
                .size("small")
                .onClick(_ => setDecidedReceiptIds(decidedReceiptIds + o.receiptId))
            )
          case None => Fragment()
        },
        savedExpenseId match {
          case Some(expenseId) =>
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "8px" })(
              mui.Button.normal()("Close").size("small").onClick(_ => props.onSuccess(expenseId))
            )
          case None =>
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "8px" })(
              mui.Button
                .normal()(if (submit.isSubmitting) "Saving…" else "Save")
                .variant("contained")
                .size("small")
                .disabled(submit.buttonDisabled(formData.isValid))
                .`type`("submit")
                .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" }),
              mui.Button.normal()("Cancel").size("small").disabled(submit.isSubmitting).onClick(_ => props.onCancel())
            )
        }
      )
    )
  }
}
