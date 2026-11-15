package net.wiringbits.components.pages.bankstatementreview

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.{CreateRefundFromRow, GetBankStatement}
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.components.pages.{CategoryResolver, MerchantResolver}
import net.wiringbits.forms.BankRowRefundFormData
import net.wiringbits.ui.components.inputs.{
  AmountInput,
  DateInput,
  OptionalCategoryAutocompleteInput,
  OptionalMerchantAutocompleteInput
}
import net.wiringbits.ui.forms.{ExistingOrNewValue, ExpLocalDate, PositiveBigDecimal, SubmitHooks}
import net.wiringbits.webapp.common.validators.ValidationResult
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, onSubmit}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object BankRowCreateRefundForm {
  def apply(props: Props): KeyAddingStage = component(props)

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
    val verdict = BankRowCreateExpenseForm.rowVerdict(row, ruleList, merchants)
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
    val (formData, setFormData) = Hooks.useState(
      BankRowRefundFormData.initial(
        row = props.row,
        matchedMerchant = matchedMerchant,
        initialCategory = initCategoryIdOpt.flatMap(cid => props.categories.find(_.id == cid)),
        notes = BankRowCreateExpenseForm.initialNotes(
          props.row,
          BankRowCreateExpenseForm.rowVerdict(props.row, props.ruleList, props.merchants)
        )
      )
    )
    val submit = SubmitHooks.useSubmit()

    def handleSubmit(): Unit =
      formData.submitRequest match {
        case None =>
          submit.fail(formData.formValidationErrors.headOption.getOrElse("Please fix the highlighted fields"))
        case Some(resolved) =>
          submit.begin()
          val fResult: Future[UUID] = for {
            categoryId <- CategoryResolver
              .findOrCreateCategoryOpt(props.ctx.api.client, props.categories, resolved.category)
            merchant <- MerchantResolver
              .findOrCreateMerchantOpt(props.ctx.api.client, props.merchants, resolved.merchant, categoryId)
            resp <- props.ctx.api.client.createRefundFromRow(
              bankStatementId = props.stmtId,
              rowId = props.rowId,
              CreateRefundFromRow.Request(
                amount = resolved.amount,
                date = resolved.date,
                merchantId = merchant.map(_._1),
                categoryId = categoryId,
                expenseId = None,
                notes = resolved.notes
              )
            )
          } yield resp.refundId

          fResult.onComplete {
            case Success(refundId) => props.onSuccess(refundId)
            case Failure(ex) => submit.fail(ex.getMessage)
          }
      }

    def updateAmount(value: ValidationResult[PositiveBigDecimal]): Unit =
      setFormData(cur => cur.copy(amount = cur.amount.updated(value)))

    def updateDate(value: ValidationResult[ExpLocalDate]): Unit =
      setFormData(cur => cur.copy(date = cur.date.updated(value)))

    def updateMerchant(value: ValidationResult[Option[ExistingOrNewValue[MerchantName, UUID]]]): Unit =
      setFormData(cur => cur.selectMerchant(value, props.merchants, props.categories))

    def updateCategory(value: ValidationResult[Option[ExistingOrNewValue[CategoryName, UUID]]]): Unit =
      setFormData(cur => cur.copy(category = cur.category.updated(value)))

    form(onSubmit := { e =>
      e.preventDefault()
      handleSubmit()
    })(
      mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "12px" })(
        mui
          .Typography("New refund")
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
              label = "Merchant (optional)",
              onChange = updateMerchant,
              disabled = submit.isSubmitting
            )
          ): ReactElement),
        (OptionalCategoryAutocompleteInput
          .component(
            OptionalCategoryAutocompleteInput.Props(
              field = formData.category,
              options = props.categories,
              label = "Category (optional)",
              onChange = updateCategory,
              disabled = submit.isSubmitting
            )
          ): ReactElement),
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
      )
    )
  }
}
