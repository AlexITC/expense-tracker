package net.wiringbits.components.pages.bankstatementreview
import net.wiringbits.components.pages.AutocompleteLabels

import com.olvind.mui.muiMaterial.autocompleteAutocompleteMod.AutocompleteRenderInputParams
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.{CreateRefundFromRow, GetBankStatement}
import net.wiringbits.api.models.categories.{CreateCategory, GetCategories}
import net.wiringbits.api.models.merchants.{CreateMerchant, GetMerchants}
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.compat.MuiCompat
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, onSubmit}

import java.time.LocalDate
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object BankRowCreateRefundForm {
  def apply(props: Props): KeyAddingStage = component(props)

  private def renderTextField(label: String)(params: AutocompleteRenderInputParams): ReactElement =
    (mui.TextField.outlined
      .withProps(MuiCompat.textFieldProps(params, label, size = Some("small")))
      .withKey("tf"): ReactElement)

  case class Props(
      stmtId: UUID,
      rowId: UUID,
      row: GetBankStatement.StatementRowData,
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category],
      ruleList: List[ImportRuleMatcher.Rule],
      ctx: AppContext,
      onSuccess: UUID => Unit,
      onCancel: () => Unit
  )

  /** The merchant and category the form starts with: the row's resolved refund
    * (`BankStatementReviewLogic.resolveRefund` over the effective rules, the same verdict the review table uses). Ids
    * that are not among the user's merchants/categories are dropped.
    */
  def initialSelection(
      row: GetBankStatement.StatementRowData,
      ruleList: List[ImportRuleMatcher.Rule],
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category]
  ): (Option[GetMerchants.Response.Merchant], Option[UUID]) = {
    val verdict = RowRuleVerdict.verdict(
      ImportRuleMatcher.effectiveRules(ruleList, merchants.map(m => m.id -> m.name)),
      row.description,
      row.amount
    )
    val prefill = BankStatementReviewLogic.resolveRefund(verdict, merchants, row)
    (
      prefill.flatMap(_.merchantId).flatMap(mid => merchants.find(_.id == mid)),
      prefill.flatMap(_.categoryId).filter(cid => categories.exists(_.id == cid))
    )
  }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (matchedMerchant, initCategoryIdOpt) =
      initialSelection(props.row, props.ruleList, props.merchants, props.categories)

    val initMerchantName = matchedMerchant.map(_.name).getOrElse("")
    val initMerchantIdOpt = matchedMerchant.map(_.id)
    val initCategoryName =
      initCategoryIdOpt.flatMap(cid => props.categories.find(_.id == cid)).map(_.name).getOrElse("")

    val (amount, setAmount) = Hooks.useState(props.row.amount.abs.toString)
    val (date, setDate) = Hooks.useState(props.row.date.toString)
    val (notes, setNotes) = Hooks.useState(props.row.description)

    val (merchantInput, setMerchantInput) = Hooks.useState(initMerchantName)
    val (merchantIdOpt, setMerchantIdOpt) = Hooks.useState[Option[UUID]](initMerchantIdOpt)

    val (categoryInput, setCategoryInput) = Hooks.useState(initCategoryName)
    val (categoryIdOpt, setCategoryIdOpt) = Hooks.useState[Option[UUID]](initCategoryIdOpt)

    val (error, setError) = Hooks.useState("")

    def selectMerchant(m: GetMerchants.Response.Merchant): Unit = {
      setMerchantInput(m.name)
      setMerchantIdOpt(Some(m.id))
      m.categoryId.foreach { cid =>
        setCategoryIdOpt(Some(cid))
        props.categories.find(_.id == cid).foreach(c => setCategoryInput(c.name))
      }
    }

    def handleSubmit(): Unit =
      (Try(BigDecimal(amount)).toOption, Try(LocalDate.parse(date)).toOption) match {
        case (None, _) => setError("Invalid amount")
        case (_, None) => setError("Invalid date")
        case (Some(amt), Some(dt)) =>
          setError("")
          val merchantName = merchantInput.trim
          val categoryName = categoryInput.trim

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
                      .map(r => Some(r.id))
                }
            }
            resp <- props.ctx.api.client.createRefundFromRow(
              bankStatementId = props.stmtId,
              rowId = props.rowId,
              CreateRefundFromRow.Request(
                amount = amt,
                date = dt,
                merchantId = resolvedMerchantId,
                categoryId = resolvedCategoryId,
                expenseId = None,
                notes = Some(notes).filter(_.nonEmpty)
              )
            )
          } yield props.onSuccess(resp.refundId)

          fResult.onComplete {
            case Success(_) => ()
            case Failure(ex) => setError(ex.getMessage)
          }
      }

    val merchantAcProps = MuiCompat.freeSoloAutocompleteProps(
      options = props.merchants,
      renderInput = renderTextField("Merchant (optional)"),
      inputValue = merchantInput,
      getOptionLabel = AutocompleteLabels.merchantOptionLabel,
      onSelect = value =>
        (value: Any) match {
          case m: GetMerchants.Response.Merchant => selectMerchant(m)
          case _ => ()
        },
      onInput = (text, rsn) => {
        setMerchantInput(text)
        if (rsn != "reset") setMerchantIdOpt(None)
      },
      disabled = false,
      fullWidth = true
    )

    val categoryAcProps = MuiCompat.freeSoloAutocompleteProps(
      options = props.categories,
      renderInput = renderTextField("Category (optional)"),
      inputValue = categoryInput,
      getOptionLabel = AutocompleteLabels.categoryOptionLabel,
      onSelect = value =>
        (value: Any) match {
          case c: GetCategories.Response.Category => setCategoryIdOpt(Some(c.id)); setCategoryInput(c.name)
          case _ => ()
        },
      onInput = (text, rsn) => {
        setCategoryInput(text)
        if (rsn != "reset") setCategoryIdOpt(None)
      },
      disabled = false,
      fullWidth = true
    )

    form(onSubmit := { e =>
      e.preventDefault()
      handleSubmit()
    })(
      mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "12px" })(
        mui
          .Typography("New refund")
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
  }
}
