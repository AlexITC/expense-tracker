package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.{AiSuggestions, CreateExpenseFromRow, GetBankStatement}
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.{CreateImportRule, CreateMerchant, GetMerchants, ImportRuleAction}
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, label, onSubmit}

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.util.{Failure, Success, Try}

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
      aliasToCreate(pattern, "")
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
    val (merchantOpen, setMerchantOpen) = Hooks.useState(false)

    val (categoryInput, setCategoryInput) = Hooks.useState(initCategoryName)
    val (categoryIdOpt, setCategoryIdOpt) = Hooks.useState[Option[UUID]](initCategoryIdOpt)
    val (categoryOpen, setCategoryOpen) = Hooks.useState(false)

    val (rulePattern, setRulePattern) = Hooks.useState(props.row.description)
    val (categoryRuleOptIn, setCategoryRuleOptIn) = Hooks.useState(false)
    val (receiptId, setReceiptId) = Hooks.useState[Option[UUID]](None)
    val (receipts, setReceipts) = Hooks.useState[List[GetReceipts.Response.Receipt]](Nil)
    val (error, setError) = Hooks.useState("")
    // Set once the expense exists but its alias rule failed: the form stays open only to show that error.
    val (savedExpenseId, setSavedExpenseId) = Hooks.useState[Option[UUID]](None)

    val merchantName = merchantInput.trim
    val hint = ruleHint(props.row, props.ruleList, merchantName)
    val showCategoryRuleOption = merchantName.isEmpty && categoryInput.trim.nonEmpty && hint == RuleHint.NeedsAlias

    // A suggestion can arrive (via polling) after the form was opened blank; apply it only while the
    // merchant/category fields are still untouched so user edits are never overwritten.
    val aiSuggestionKey = props.aiSuggestion.map(s => s"${s.merchantId}|${s.categoryId}").getOrElse("")
    Hooks.useEffect(
      () => {
        if (isPristine(props.row, merchantIdOpt, merchantInput, categoryIdOpt, categoryInput)) {
          initMerchant.foreach { m =>
            setMerchantInput(m.name)
            setMerchantIdOpt(Some(m.id))
          }
          initCategoryIdOpt.foreach { cid =>
            setCategoryIdOpt(Some(cid))
            setCategoryInput(initCategoryName)
          }
        }
      },
      js.Array(aiSuggestionKey)
    )

    Hooks.useEffect(
      () => {
        props.ctx.api.client.getReceipts.onComplete {
          case Success(resp) => setReceipts(resp.data.filter(r => r.expenseId.isEmpty && r.amount.isDefined))
          case Failure(_) => ()
        }
        ()
      },
      ""
    )

    val filteredMerchants = props.merchants
      .filter(m => merchantInput.isEmpty || m.name.toLowerCase.contains(merchantInput.toLowerCase))
      .take(6)

    val filteredCategories = props.categories
      .filter(c => categoryInput.isEmpty || c.name.toLowerCase.contains(categoryInput.toLowerCase))
      .take(6)

    def selectMerchant(m: GetMerchants.Response.Merchant): Unit = {
      setMerchantInput(m.name)
      setMerchantIdOpt(Some(m.id))
      setMerchantOpen(false)
      m.categoryId.foreach { cid =>
        setCategoryIdOpt(Some(cid))
        props.categories.find(_.id == cid).foreach(c => setCategoryInput(c.name))
      }
    }

    def rankReceipts(receipts: List[GetReceipts.Response.Receipt]): List[(GetReceipts.Response.Receipt, Int)] = {
      val rowAmt = props.row.amount.abs
      val rowDateStr = props.row.date.toString
      val rowDesc = props.row.description.toLowerCase
      receipts
        .flatMap { r =>
          r.amount.map { rAmt =>
            val amtMatch = if (rAmt == rowAmt) 4 else 0
            val dateMatch = if (r.date.exists(_.toString == rowDateStr)) 2 else 0
            val merchMatch = merchantIdOpt
              .flatMap(id => props.merchants.find(_.id == id))
              .map(_.name) match {
              case Some(mn)
                  if r.merchantName
                    .exists(rn => rn.toLowerCase.contains(mn.toLowerCase) || rowDesc.contains(rn.toLowerCase)) =>
                1
              case _ => 0
            }
            (r, amtMatch + dateMatch + merchMatch)
          }
        }
        .filter { case (_, score) => score >= 4 || (score >= 2 && score < 4) }
        .sortBy { case (_, score) => -score }
    }

    def handleSubmit(): Unit =
      (Try(BigDecimal(amount)).toOption, Try(LocalDate.parse(date)).toOption) match {
        case (None, _) => setError("Invalid amount")
        case (_, None) => setError("Invalid date")
        case (Some(amt), Some(dt)) =>
          setError("")
          val categoryName = categoryInput.trim

          val fExpense: Future[(UUID, Option[UUID], Option[UUID])] = for {
            resolvedCategoryId <- categoryName match {
              case "" => Future.successful[Option[UUID]](None)
              case _ =>
                DescriptionRow
                  .resolveOrCreateCategory(props.ctx.api.client, props.categories, categoryIdOpt, categoryName)
                  .map { case (id, _) => Some(id) }
            }

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
            resp <- props.ctx.api.client.createExpenseFromRow(props.stmtId, props.row.id, req)
          } yield (resp.expenseId, resolvedMerchantId, resolvedCategoryId)

          fExpense.onComplete {
            case Failure(ex) => setError(ex.getMessage)
            case Success((expenseId, resolvedMerchantId, resolvedCategoryId)) =>
              val aliasOpt = if (hint == RuleHint.NeedsAlias) aliasToCreate(rulePattern, merchantName) else None
              val categoryRuleOpt =
                categoryRuleToCreate(hint, merchantName, resolvedCategoryId, categoryRuleOptIn, rulePattern)
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

    def handleViewTicket(receiptId: UUID, filename: String): Unit = {
      val win = dom.window.open("", "_blank")
      val mimeType = filename.toLowerCase match {
        case f if f.endsWith(".pdf") => "application/pdf"
        case f if f.endsWith(".jpg") || f.endsWith(".jpeg") => "image/jpeg"
        case f if f.endsWith(".png") => "image/png"
        case _ => "application/octet-stream"
      }
      props.ctx.api.client.getReceiptFile(receiptId).onComplete {
        case Success((bytes, _, _)) =>
          val typedArray = new js.typedarray.Uint8Array(bytes.length)
          bytes.indices.foreach(i => typedArray(i) = (bytes(i) & 0xff).toShort)
          val blob = new dom.Blob(
            js.Array(typedArray.buffer.asInstanceOf[js.typedarray.ArrayBuffer]),
            new dom.BlobPropertyBag { `type` = mimeType }
          )
          val url = dom.URL.createObjectURL(blob)
          if (win != null) win.location.href = url
          dom.window.setTimeout(() => dom.URL.revokeObjectURL(url), 60000)
        case Failure(ex) =>
          if (win != null) win.close()
          setError(ex.getMessage)
      }
    }

    val dropdownItemSx = new CSSPropertiesUtils {
      padding = "8px 12px"; cursor = "pointer"; fontSize = "14px"
    }
    val dropdownPaperSx = new CSSPropertiesUtils {
      position = "absolute"; top = "100%"; left = "0"; right = "0"; zIndex = "1400"
      maxHeight = "200px"; overflowY = "auto"
      border = "1px solid var(--lc-surface-2)"; borderRadius = "4px"; backgroundColor = "var(--lc-paper)"
      boxShadow = "0 4px 12px rgba(0,0,0,0.1)"
    }

    val rankedReceipts = rankReceipts(receipts)
    val receiptSuggestStyling = new CSSPropertiesUtils { fontSize = "0.8em"; color = "#555" }
    val receiptSelectedStyling = new CSSPropertiesUtils {
      fontSize = "0.8em"; color = "var(--lc-link)"; fontWeight = "bold"
    }
    val anyOpen = merchantOpen || categoryOpen
    val hintStyling = new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text-2)" }

    form(onSubmit := { e =>
      e.preventDefault()
      if (savedExpenseId.isEmpty) handleSubmit()
    })(
      Fragment(
        if (anyOpen)
          (mui.Box
            .sx(new CSSPropertiesUtils {
              position = "fixed"; top = "0"; left = "0"; right = "0"; bottom = "0"; zIndex = "1399"
            })
            .onClick(_ => { setMerchantOpen(false); setCategoryOpen(false) })(): ReactElement)
        else Fragment(),
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
          // Merchant type-ahead
          mui.Box.sx(new CSSPropertiesUtils { position = "relative" })(
            mui.TextField
              .outlined()
              .label("Merchant")
              .size("small")
              .value(merchantInput)
              .onChange { e =>
                val v = e.target.asInstanceOf[dom.HTMLInputElement].value
                setMerchantInput(v)
                setMerchantIdOpt(None)
                setMerchantOpen(true)
              }
              .sx(new CSSPropertiesUtils { width = "100%" }),
            if (merchantOpen && filteredMerchants.nonEmpty)
              (mui.Box.sx(dropdownPaperSx)(
                Fragment(
                  filteredMerchants.map { m =>
                    (mui.Box
                      .sx(dropdownItemSx)
                      .onClick { e =>
                        e.stopPropagation()
                        selectMerchant(m)
                      }(mui.Typography(m.name).sx(new CSSPropertiesUtils { fontSize = "14px" }))
                      .withKey(m.id.toString): ReactElement)
                  }*
                )
              ): ReactElement)
            else Fragment()
          ),
          // Category type-ahead
          mui.Box.sx(new CSSPropertiesUtils { position = "relative" })(
            mui.TextField
              .outlined()
              .label("Category")
              .size("small")
              .value(categoryInput)
              .onChange { e =>
                val v = e.target.asInstanceOf[dom.HTMLInputElement].value
                setCategoryInput(v)
                setCategoryIdOpt(None)
                setCategoryOpen(true)
              }
              .sx(new CSSPropertiesUtils { width = "100%" }),
            if (categoryOpen && filteredCategories.nonEmpty)
              (mui.Box.sx(dropdownPaperSx)(
                Fragment(
                  filteredCategories.map { c =>
                    (mui.Box
                      .sx(dropdownItemSx)
                      .onClick { e =>
                        e.stopPropagation()
                        setCategoryInput(c.name)
                        setCategoryIdOpt(Some(c.id))
                        setCategoryOpen(false)
                      }(mui.Typography(c.name).sx(new CSSPropertiesUtils { fontSize = "14px" }))
                      .withKey(c.id.toString): ReactElement)
                  }*
                )
              ): ReactElement)
            else Fragment()
          ),
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
                        .onClick(_ => handleViewTicket(r.id, r.documentFilename))
                    )
                    .withKey(r.id.toString): ReactElement)
                }*
              )
            )
          else Fragment(),
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
    )
  }
}
