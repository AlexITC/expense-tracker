package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.{AiSuggestions, CreateExpenseFromRow, GetBankStatement}
import net.wiringbits.api.models.categories.{CreateCategory, GetCategories}
import net.wiringbits.api.models.merchants.{CreateImportRule, CreateMerchant, GetMerchants, ImportRuleAction}
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, onSubmit}

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

  /** Initial (merchant, categoryId) for the form: a SetMerchant rule winner first, then the AI suggestion. Suggested
    * ids that are not among the user's merchants/categories are dropped.
    */
  def initialSelection(
      row: GetBankStatement.StatementRowData,
      ruleList: List[ImportRuleMatcher.Rule],
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category],
      aiSuggestion: Option[AiSuggestions.RowSuggestion]
  ): (Option[GetMerchants.Response.Merchant], Option[UUID]) = {
    val ruleMerchant = ImportRuleMatcher
      .findRowWinner(ruleList, row.description)
      .filter(_.action == ImportRuleAction.SetMerchant)
      .flatMap(_.merchantId)
      .flatMap(mid => merchants.find(_.id == mid))

    ruleMerchant match {
      case Some(m) => (Some(m), m.categoryId)
      case None =>
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

  /** Whether the merchant/category fields still hold the values a blank form starts with (no rule/AI prefill). */
  def isPristine(
      row: GetBankStatement.StatementRowData,
      merchantIdOpt: Option[UUID],
      merchantInput: String,
      categoryIdOpt: Option[UUID],
      categoryInput: String
  ): Boolean =
    merchantIdOpt.isEmpty && merchantInput == row.description && categoryIdOpt.isEmpty && categoryInput.isEmpty

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (initMerchant, initCategoryIdOpt) =
      initialSelection(props.row, props.ruleList, props.merchants, props.categories, props.aiSuggestion)

    val initMerchantName = initMerchant.map(_.name).getOrElse(props.row.description)
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

    val (useDescAsPattern, setUseDescAsPattern) = Hooks.useState(true)
    val (receiptId, setReceiptId) = Hooks.useState[Option[UUID]](None)
    val (receipts, setReceipts) = Hooks.useState[List[GetReceipts.Response.Receipt]](Nil)
    val (error, setError) = Hooks.useState("")

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
          val merchantName = merchantInput.trim
          val categoryName = categoryInput.trim
          val pattern = if (useDescAsPattern) props.row.description else merchantName

          val fResult: Future[Unit] = for {
            resolvedCategoryId <- categoryName match {
              case "" => Future.successful[Option[UUID]](None)
              case _ =>
                categoryIdOpt match {
                  case Some(id) => Future.successful(Some(id))
                  case None =>
                    props.ctx.api.client
                      .createCategory(CreateCategory.Request(name = categoryName))
                      .map(r => Some(r.id))
                }
            }

            merchantResult <- merchantName match {
              case "" => Future.successful[Option[(UUID, Option[GetMerchants.Response.Merchant])]](None)
              case _ =>
                merchantIdOpt match {
                  case Some(id) =>
                    Future.successful(Some((id, None: Option[GetMerchants.Response.Merchant])))
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
                        Some((r.id, Some(m)))
                      }
                }
            }

            resolvedMerchantId = merchantResult.map(_._1)

            ruleIdOpt <-
              if (resolvedMerchantId.isDefined && pattern.nonEmpty)
                props.ctx.api.client
                  .createImportRule(
                    CreateImportRule.Request(
                      action = ImportRuleAction.SetMerchant,
                      pattern = pattern,
                      merchantId = resolvedMerchantId
                    )
                  )
                  .map(r => Option(r.id))
                  .recover { case _ => None }
              else Future.successful[Option[UUID]](None)

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
          } yield {
            val newRule = for {
              mid <- resolvedMerchantId
              rid <- ruleIdOpt
            } yield ImportRuleMatcher.Rule(
              id = rid,
              action = ImportRuleAction.SetMerchant,
              pattern = pattern,
              merchantId = Some(mid)
            )
            val newMerchant = merchantResult.flatMap(_._2)
            props.onNewEntities(newRule, newMerchant)
            props.onSuccess(resp.expenseId)
          }

          fResult.onComplete {
            case Success(_) => ()
            case Failure(ex) => setError(ex.getMessage)
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

    form(onSubmit := { e =>
      e.preventDefault()
      handleSubmit()
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
          (mui.Box
            .sx(new CSSPropertiesUtils {
              display = "flex"; alignItems = "center"; cursor = "pointer"; gap = "4px"
            })
            .onClick(_ => setUseDescAsPattern(!useDescAsPattern))(
              (mui.Checkbox
                .checked(useDescAsPattern)
                .size("small")
                .onChange((_, checked) => setUseDescAsPattern(checked))
                .build: ReactElement),
              mui
                .Typography("Use original description as rule pattern")
                .sx(new CSSPropertiesUtils { fontSize = "13px" })
            ): ReactElement),
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
          mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "8px" })(
            mui.Button
              .normal()("Save")
              .variant("contained")
              .size("small")
              .`type`("submit")
              .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" }),
            mui.Button.normal()("Cancel").size("small").onClick(_ => props.onCancel())
          )
        )
      )
    )
  }
}
