package net.wiringbits.components.pages.bankstatementreview
import net.wiringbits.components.pages.{AutocompleteLabels, CategoryResolver}

import com.olvind.mui.muiMaterial.autocompleteAutocompleteMod.{AutocompleteProps, AutocompleteRenderInputParams}
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.{AiSuggestions, CreateExpenseFromRow, GetBankStatement}
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.{CreateImportRule, CreateMerchant, GetMerchants, ImportRuleAction}
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}
import net.wiringbits.core.SafeHooks
import net.wiringbits.utils.ReceiptFileViewer
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.compat.MuiCompat
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, label, onSubmit}

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.{Failure, Success, Try}

object BankRowCreateExpenseForm {
  def apply(props: Props): KeyAddingStage = component(props)

  private def renderTextField(label: String)(params: AutocompleteRenderInputParams): ReactElement =
    (mui.TextField.outlined
      .withProps(MuiCompat.textFieldProps(params, label, size = Some("small")))
      .withKey("tf"): ReactElement)

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
    * see `ImportRuleMatcher.effectiveRules`), then a SetCategory rule winner's category (no merchant, AI not
    * consulted), then the AI suggestion. Ids that are not among the user's merchants/categories are dropped.
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
      case (Some(m), _) => (Some(m), m.categoryId)
      case (None, RowRuleVerdict.SetCategory(categoryId)) =>
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
      row.description
    )

  /** The merchant text the form starts with when no merchant is prefilled: the row description, except for a row a
    * SetCategory rule handles, which starts blank so saving does not create a merchant named after the description.
    */
  def initialMerchantInput(row: GetBankStatement.StatementRowData, verdict: RowRuleVerdict.Verdict): String =
    verdict match {
      case RowRuleVerdict.SetCategory(_) => ""
      case _ => row.description
    }

  /** Whether the merchant/category fields still hold the values a blank form starts with (no rule/AI prefill). */
  def isPristine(
      row: GetBankStatement.StatementRowData,
      merchantIdOpt: Option[UUID],
      merchantInput: String,
      categoryIdOpt: Option[UUID],
      categoryInput: String
  ): Boolean =
    merchantIdOpt.isEmpty && merchantInput == row.description && categoryIdOpt.isEmpty && categoryInput.isEmpty

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
    ImportRuleMatcher.findRowWinner(explicitRules, row.description) match {
      case Some(rule) => RuleHint.CoveredByRule(rule.pattern)
      case None =>
        val nameRules = ImportRuleMatcher.effectiveRules(Nil, List(new UUID(0L, 0L) -> merchantName))
        if (ImportRuleMatcher.findMatches(nameRules, row.description).nonEmpty) RuleHint.MatchesByName
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

    val initMerchantName =
      initMerchant
        .map(_.name)
        .getOrElse(initialMerchantInput(props.row, rowVerdict(props.row, props.ruleList, props.merchants)))
    val initMerchantIdOpt = initMerchant.map(_.id)
    val initCategoryName =
      initCategoryIdOpt.flatMap(cid => props.categories.find(_.id == cid)).map(_.name).getOrElse("")

    val (amount, setAmount) = Hooks.useState(props.row.amount.abs.toString)
    val (date, setDate) = Hooks.useState(props.row.date.toString)
    val (notes, setNotes) = Hooks.useState(props.row.description)

    val (merchantInput, setMerchantInput) = Hooks.useState(initMerchantName)
    val (merchantIdOpt, setMerchantIdOpt) = Hooks.useState[Option[UUID]](initMerchantIdOpt)

    val (categoryInput, setCategoryInput) = Hooks.useState(initCategoryName)
    val (categoryIdOpt, setCategoryIdOpt) = Hooks.useState[Option[UUID]](initCategoryIdOpt)

    val (rulePattern, setRulePattern) = Hooks.useState(props.row.description)
    val (categoryRuleOptIn, setCategoryRuleOptIn) = Hooks.useState(false)
    val (receiptId, setReceiptId) = Hooks.useState[Option[UUID]](None)
    val (receipts, setReceipts) = Hooks.useState[List[GetReceipts.Response.Receipt]](Nil)
    // Receipts whose merchant offer the user already accepted or declined.
    val (decidedReceiptIds, setDecidedReceiptIds) = Hooks.useState[Set[UUID]](Set.empty)
    val (error, setError) = Hooks.useState("")
    // Set once the expense exists but its alias rule failed: the form stays open only to show that error.
    val (savedExpenseId, setSavedExpenseId) = Hooks.useState[Option[UUID]](None)

    val merchantName = merchantInput.trim
    val hint = ruleHint(props.row, props.ruleList, merchantName)
    val showCategoryRuleOption = merchantName.isEmpty && categoryInput.trim.nonEmpty && hint == RuleHint.NeedsAlias

    // A suggestion can arrive (via polling) after the form was opened blank; apply it only while the
    // merchant/category fields are still untouched so user edits are never overwritten.
    val aiSuggestionKey = props.aiSuggestion.map(s => s"${s.merchantId}|${s.categoryId}").getOrElse("")
    SafeHooks.useEffect(aiSuggestionKey)(() => {
      if (
        isPristine(
          props.row,
          merchantIdOpt = merchantIdOpt,
          merchantInput = merchantInput,
          categoryIdOpt = categoryIdOpt,
          categoryInput = categoryInput
        )
      ) {
        initMerchant.foreach { m =>
          setMerchantInput(m.name)
          setMerchantIdOpt(Some(m.id))
        }
        initCategoryIdOpt.foreach { cid =>
          setCategoryIdOpt(Some(cid))
          setCategoryInput(initCategoryName)
        }
      }
    })

    SafeHooks.useEffect()(() => {
      props.ctx.api.client.getReceipts.onComplete {
        case Success(resp) => setReceipts(resp.data.filter(r => r.expenseId.isEmpty && r.amount.isDefined))
        case Failure(_) => ()
      }
      ()
    })

    def selectMerchant(m: GetMerchants.Response.Merchant): Unit = {
      setMerchantInput(m.name)
      setMerchantIdOpt(Some(m.id))
      m.categoryId.foreach { cid =>
        setCategoryIdOpt(Some(cid))
        props.categories.find(_.id == cid).foreach(c => setCategoryInput(c.name))
      }
    }

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
      (Try(BigDecimal(amount)).toOption, Try(LocalDate.parse(date)).toOption) match {
        case (None, _) => setError("Invalid amount")
        case (_, None) => setError("Invalid date")
        case _ if categoryInput.trim.isEmpty => setError("Choose a category")
        case (Some(amt), Some(dt)) =>
          setError("")
          val categoryName = categoryInput.trim

          val fExpense: Future[(UUID, Option[UUID], Option[UUID])] = for {
            resolvedCategoryId <- CategoryResolver
              .resolveOrCreateCategory(props.ctx.api.client, props.categories, categoryIdOpt, categoryName)
              .map { case (id, _) => Some(id) }

            resolvedMerchantId <- merchantName match {
              case "" => Future.successful[Option[UUID]](None)
              case _ =>
                merchantIdOpt match {
                  case Some(id) => Future.successful(Some(id))
                  case None =>
                    props.ctx.api.client
                      .createMerchant(
                        CreateMerchant.Request(
                          name = merchantName,
                          notes = None,
                          categoryId = resolvedCategoryId,
                          taxId = None
                        )
                      )
                      .map { r =>
                        val m = GetMerchants.Response.Merchant(
                          id = r.id,
                          name = r.name,
                          notes = None,
                          categoryId = resolvedCategoryId,
                          taxId = None,
                          reviewed = false,
                          createdAt = Instant.now()
                        )
                        props.onNewEntities(None, Some(m))
                        // A retry after a later failure must reuse this merchant instead of creating another one.
                        setMerchantIdOpt(Some(r.id))
                        Some(r.id)
                      }
                }
            }

            splits = resolvedCategoryId
              .map(catId => List(CreateExpenseFromRow.Split(catId, amt)))
              .getOrElse(Nil)
            req = CreateExpenseFromRow.Request(
              amount = amt,
              date = dt,
              time = None,
              merchantId = resolvedMerchantId,
              notes = Some(notes).filter(_.nonEmpty),
              splits = splits,
              receiptId = receiptId
            )
            resp <- props.ctx.api.client.createExpenseFromRow(bankStatementId = props.stmtId, rowId = props.row.id, req)
          } yield (resp.expenseId, resolvedMerchantId, resolvedCategoryId)

          fExpense.onComplete {
            case Failure(ex) => setError(ex.getMessage)
            case Success((expenseId, resolvedMerchantId, resolvedCategoryId)) =>
              val aliasOpt =
                if (hint == RuleHint.NeedsAlias) aliasToCreate(pattern = rulePattern, merchantName = merchantName)
                else None
              val categoryRuleOpt =
                categoryRuleToCreate(
                  hint,
                  merchantName = merchantName,
                  resolvedCategoryId,
                  categoryRuleOptIn,
                  pattern = rulePattern
                )
              val ruleRequestOpt = (resolvedMerchantId, aliasOpt, resolvedCategoryId, categoryRuleOpt) match {
                case (Some(mid), Some(alias), _, _) =>
                  Some(
                    CreateImportRule.Request(
                      action = ImportRuleAction.SetMerchant(mid),
                      pattern = alias
                    )
                  )
                case (None, _, Some(catId), Some(pattern)) =>
                  Some(
                    CreateImportRule.Request(
                      action = ImportRuleAction.SetCategory(catId),
                      pattern = pattern
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
                              ruleRequest.pattern
                            )
                          ),
                          None
                        )
                        props.onSuccess(expenseId)
                      case Failure(ex) =>
                        setSavedExpenseId(Some(expenseId))
                        setError(s"Expense created, but the rule could not be created: ${ex.getMessage}")
                    }
                case None => props.onSuccess(expenseId)
              }
          }
      }

    val merchantAcProps = AutocompleteProps[GetMerchants.Response.Merchant, Boolean, Boolean, Boolean, "div"](
      options = props.merchants.toJSArray,
      renderInput = renderTextField("Merchant")
    )
    val merchantDyn = merchantAcProps.asInstanceOf[js.Dynamic]
    merchantDyn.freeSolo = true
    merchantDyn.size = "small"
    merchantDyn.fullWidth = true
    merchantDyn.inputValue = merchantInput
    merchantDyn.getOptionLabel = (opt: js.Any) => AutocompleteLabels.merchantOptionLabel(opt)
    merchantDyn.onChange = (_: js.Any, value: js.Any, _: js.Any, _: js.Any) =>
      (value: Any) match {
        case m: GetMerchants.Response.Merchant => selectMerchant(m)
        case _ => ()
      }
    merchantDyn.onInputChange = (_: js.Any, value: js.Any, reason: js.Any) => {
      val (text, rsn) = MuiCompat.autocompleteInputChange(value = value, reason = reason)
      setMerchantInput(text)
      if (rsn != "reset") setMerchantIdOpt(None)
    }

    val categoryAcProps = AutocompleteProps[GetCategories.Response.Category, Boolean, Boolean, Boolean, "div"](
      options = props.categories.toJSArray,
      renderInput = renderTextField("Category")
    )
    val categoryDyn = categoryAcProps.asInstanceOf[js.Dynamic]
    categoryDyn.freeSolo = true
    categoryDyn.size = "small"
    categoryDyn.fullWidth = true
    categoryDyn.inputValue = categoryInput
    categoryDyn.getOptionLabel = (opt: js.Any) => AutocompleteLabels.categoryOptionLabel(opt)
    categoryDyn.onChange = (_: js.Any, value: js.Any, _: js.Any, _: js.Any) =>
      (value: Any) match {
        case c: GetCategories.Response.Category => setCategoryIdOpt(Some(c.id)); setCategoryInput(c.name)
        case _ => ()
      }
    categoryDyn.onInputChange = (_: js.Any, value: js.Any, reason: js.Any) => {
      val (text, rsn) = MuiCompat.autocompleteInputChange(value = value, reason = reason)
      setCategoryInput(text)
      if (rsn != "reset") setCategoryIdOpt(None)
    }

    val rankedReceipts = rankReceipts(receipts)
    val receiptSuggestStyling = new CSSPropertiesUtils { fontSize = "0.8em"; color = "#555" }
    val receiptSelectedStyling = new CSSPropertiesUtils {
      fontSize = "0.8em"; color = "var(--lc-link)"; fontWeight = "bold"
    }
    val offer = BankStatementReviewLogic.receiptOffer(
      receipts.find(r => receiptId.contains(r.id)),
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
        if (error.nonEmpty)
          mui.Typography(error).sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px" })
        else Fragment(),
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "12px" })(
          mui.TextField
            .outlined()
            .label("Amount")
            .size("small")
            .value(amount)
            .onChange(e => setAmount(e.target.asInstanceOf[dom.HTMLInputElement].value))
            .sx(new CSSPropertiesUtils { flex = "1" }),
          mui.TextField
            .outlined()
            .label("Date")
            .size("small")
            .value(date)
            .onChange(e => setDate(e.target.asInstanceOf[dom.HTMLInputElement].value))
            .sx(new CSSPropertiesUtils { flex = "1" })
        ),
        mui.TextField
          .outlined()
          .label("Notes")
          .size("small")
          .value(notes)
          .onChange(e => setNotes(e.target.asInstanceOf[dom.HTMLInputElement].value)),
        (mui.Autocomplete.withProps(merchantAcProps).withKey("merchant"): ReactElement),
        (mui.Autocomplete.withProps(categoryAcProps).withKey("category"): ReactElement),
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
                .value(rulePattern)
                .helperText("Future rows containing this text get this merchant. Leave blank for no rule.")
                .onChange(e => setRulePattern(e.target.asInstanceOf[dom.HTMLInputElement].value))
          },
        if (showCategoryRuleOption)
          mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "8px" })(
            label(
              mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; cursor = "pointer" })(
                mui.Checkbox
                  .checked(categoryRuleOptIn)
                  .size("small")
                  .onChange((_, checked) => setCategoryRuleOptIn(checked)),
                mui.Typography(s"Also categorize similar rows as ${categoryInput.trim}").sx(hintStyling)
              )
            ),
            if (categoryRuleOptIn)
              mui.TextField
                .outlined()
                .label("Rule pattern")
                .size("small")
                .value(rulePattern)
                .helperText("Future rows containing this text get this category.")
                .onChange(e => setRulePattern(e.target.asInstanceOf[dom.HTMLInputElement].value))
            else Fragment()
          )
        else Fragment(),
        if (rankedReceipts.nonEmpty)
          mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "6px" })(
            mui.Typography("Attach receipt (optional):").sx(receiptSuggestStyling),
            Fragment(
              rankedReceipts.take(5).map { case (r, _) =>
                val isSelected = receiptId.contains(r.id)
                val label =
                  s"${r.documentFilename} | ${r.amount.getOrElse("")} | ${r.date.getOrElse("")} | ${r.merchantName
                      .getOrElse("-")}"
                (mui.Box
                  .sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "8px" })(
                    mui.Typography(label).sx(if (isSelected) receiptSelectedStyling else receiptSuggestStyling),
                    if (isSelected)
                      mui.Button.normal()("Deselect").size("small").onClick(_ => setReceiptId(None))
                    else
                      mui.Button.normal()("Attach").size("small").onClick(_ => setReceiptId(Some(r.id))),
                    mui.Button
                      .normal()("View")
                      .size("small")
                      .onClick(_ =>
                        ReceiptFileViewer.openInNewTab(props.ctx.api.client, r.id, r.documentFilename, setError)
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
                  selectMerchant(o.merchant)
                  // The category follows the receipt's merchant: without a default, the previous one is cleared.
                  if (o.categoryId.isEmpty) {
                    setCategoryIdOpt(None)
                    setCategoryInput("")
                  }
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
                .normal()("Save")
                .variant("contained")
                .size("small")
                .`type`("submit")
                .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" }),
              mui.Button.normal()("Cancel").size("small").onClick(_ => props.onCancel())
            )
        }
      )
    )
  }
}
