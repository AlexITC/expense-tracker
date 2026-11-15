package net.wiringbits.components.pages.bankstatementreview
import net.wiringbits.components.pages.AutocompleteLabels

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.api.models.categories.{CreateCategory, GetCategories}
import net.wiringbits.api.models.merchants.{
  CreateImportRule,
  CreateMerchant,
  GetMerchants,
  ImportRuleAction,
  ImportRuleActionKind
}
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.forms.BankRowRuleFormData
import net.wiringbits.ui.components.inputs.{
  ExpCategoryAutocompleteInput,
  ExpMerchantAutocompleteInput,
  ExpRuleAmountInput,
  ExpRuleDescriptionInput,
  ExpRulePatternInput
}
import net.wiringbits.ui.forms.{ExpRuleAmount, ExpRuleDescription, ExpRulePattern, ExistingOrNewValue, SubmitHooks}
import net.wiringbits.webapp.common.validators.ValidationResult
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.forms.FormField
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, onSubmit}

import java.time.Instant
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object BankRowRuleForm {
  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(
      stmtId: UUID,
      row: GetBankStatement.StatementRowData,
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category],
      ctx: AppContext,
      onSuccess: (ImportRuleMatcher.Rule, Option[GetMerchants.Response.Merchant]) => Unit,
      onCancel: () => Unit
  )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (formData, setFormData) = Hooks.useState(
      BankRowRuleFormData.initial(
        patternSeed = props.row.description,
        amountSeed = props.row.amount.abs.bigDecimal.toPlainString,
        descriptionSeed = props.row.description.trim.take(ImportRuleMatcher.MaxDescriptionLength)
      )
    )
    val submit = SubmitHooks.useSubmit()

    def saveRule(
        action: ImportRuleAction,
        pattern: String,
        amount: Option[BigDecimal]
    ): Future[ImportRuleMatcher.Rule] =
      props.ctx.api.client
        .createImportRule(CreateImportRule.Request(action, pattern.trim, amount))
        .map(resp => ImportRuleMatcher.Rule(resp.id, action, pattern.trim, amount))

    // Resolving a selection to an id is the component's side effect: an existing selection already carries the id, a
    // new one is matched against the loaded rows by name and only created when it is genuinely new. The name is an
    // already-valid primitive from the field, so nothing is parsed here.
    def resolveCategory(selection: ExistingOrNewValue[CategoryName, UUID]): Future[UUID] =
      selection.id match {
        case Some(id) => Future.successful(id)
        case None =>
          props.categories.find(_.name.equalsIgnoreCase(selection.value.string)) match {
            case Some(existing) => Future.successful(existing.id)
            case None =>
              props.ctx.api.client
                .createCategory(CreateCategory.Request(name = selection.value, description = None))
                .map(_.id)
          }
      }

    def resolveCategoryOpt(selection: Option[ExistingOrNewValue[CategoryName, UUID]]): Future[Option[UUID]] =
      selection match {
        case None => Future.successful(None)
        case Some(ref) => resolveCategory(ref).map(Some(_))
      }

    def resolveMerchant(
        selection: ExistingOrNewValue[MerchantName, UUID],
        categoryId: Option[UUID]
    ): Future[(UUID, Option[GetMerchants.Response.Merchant])] =
      selection.id match {
        case Some(id) => Future.successful((id, None))
        case None =>
          props.merchants.find(_.name.equalsIgnoreCase(selection.value.string)) match {
            case Some(existing) => Future.successful((existing.id, None))
            case None =>
              props.ctx.api.client
                .createMerchant(
                  CreateMerchant.Request(name = selection.value, notes = None, categoryId = categoryId, taxId = None)
                )
                .map { resp =>
                  val created = GetMerchants.Response.Merchant(
                    id = resp.id,
                    name = resp.name,
                    notes = None,
                    categoryId = categoryId,
                    taxId = None,
                    reviewed = false,
                    createdAt = Instant.now()
                  )
                  (resp.id, Some(created))
                }
          }
      }

    def handleSave(): Unit =
      formData.submitRequest match {
        case None =>
          submit.fail(formData.formValidationErrors.headOption.getOrElse("Please fix the highlighted fields"))
        case Some(resolved) =>
          submit.begin()
          val result: Future[(ImportRuleMatcher.Rule, Option[GetMerchants.Response.Merchant])] = resolved match {
            case BankRowRuleFormData.Resolved.Ignore(pattern, amount) =>
              saveRule(ImportRuleAction.Ignore, pattern, amount).map(rule => (rule, None))

            case BankRowRuleFormData.Resolved.SetCategory(pattern, amount, description, categorySelection) =>
              resolveCategory(categorySelection).flatMap { categoryId =>
                saveRule(ImportRuleAction.SetCategory(categoryId, description = description), pattern, amount)
                  .map(rule => (rule, None))
              }

            case BankRowRuleFormData.Resolved.SetMerchant(
                  pattern,
                  amount,
                  description,
                  merchantSelection,
                  categorySelection
                ) =>
              resolveCategoryOpt(categorySelection).flatMap { categoryId =>
                resolveMerchant(merchantSelection, categoryId).flatMap { case (merchantId, created) =>
                  saveRule(
                    ImportRuleAction.SetMerchant(merchantId, categoryId = categoryId, description = description),
                    pattern,
                    amount
                  ).map(rule => (rule, created))
                }
              }
          }
          result.onComplete {
            case Success((rule, created)) => props.onSuccess(rule, created)
            case Failure(ex) => submit.fail(ex.getMessage)
          }
      }

    def updatePattern(value: ValidationResult[ExpRulePattern]): Unit =
      setFormData(cur => cur.withPattern(cur.pattern.updated(value)))

    def updateAmount(value: ValidationResult[Option[ExpRuleAmount]]): Unit =
      setFormData(cur => cur.withAmount(cur.amount.updated(value)))

    def updateDescription(value: ValidationResult[Option[ExpRuleDescription]]): Unit =
      setFormData {
        case fd: BankRowRuleFormData.SetMerchant => fd.copy(description = fd.description.updated(value))
        case fd: BankRowRuleFormData.SetCategory => fd.copy(description = fd.description.updated(value))
        case other => other
      }

    def updateMerchant(value: ValidationResult[Option[ExistingOrNewValue[MerchantName, UUID]]]): Unit =
      setFormData {
        case fd: BankRowRuleFormData.SetMerchant => fd.copy(merchant = fd.merchant.updated(value))
        case other => other
      }

    def updateCategory(value: ValidationResult[Option[ExistingOrNewValue[CategoryName, UUID]]]): Unit =
      setFormData {
        case fd: BankRowRuleFormData.SetMerchant => fd.copy(category = fd.category.updated(value))
        case fd: BankRowRuleFormData.SetCategory => fd.copy(category = fd.category.updated(value))
        case other => other
      }

    val actionItems =
      Seq(ImportRuleActionKind.SetMerchant, ImportRuleActionKind.SetCategory, ImportRuleActionKind.Ignore).map { a =>
        val label = a match {
          case ImportRuleActionKind.SetMerchant => "Set merchant"
          case ImportRuleActionKind.Ignore => "Ignore"
          case ImportRuleActionKind.SetCategory => "Set category"
        }
        mui.MenuItem.normal()(label).value(a.entryName).withKey(a.entryName).build
      }

    def patternInput: ReactElement =
      ExpRulePatternInput.component(
        ExpRulePatternInput.Props(field = formData.pattern, disabled = submit.isSubmitting, onChange = updatePattern)
      )

    def amountInput: ReactElement =
      ExpRuleAmountInput.component(
        ExpRuleAmountInput.Props(field = formData.amount, disabled = submit.isSubmitting, onChange = updateAmount)
      )

    def descriptionInput(field: FormField[Option[ExpRuleDescription]]): ReactElement =
      (ExpRuleDescriptionInput
        .component(
          ExpRuleDescriptionInput.Props(field = field, disabled = submit.isSubmitting, onChange = updateDescription)
        )
        .withKey("description"): ReactElement)

    def merchantAutocomplete(field: FormField[Option[ExistingOrNewValue[MerchantName, UUID]]]): ReactElement =
      (ExpMerchantAutocompleteInput
        .component(
          ExpMerchantAutocompleteInput.Props(
            field = field,
            options = props.merchants,
            label = "Merchant",
            getOptionLabel = AutocompleteLabels.merchantOptionLabel,
            selectExisting = value =>
              (value: Any) match {
                case m: GetMerchants.Response.Merchant => Some((m.id, m.name))
                case _ => None
              },
            onChange = updateMerchant,
            disabled = submit.isSubmitting,
            fullWidth = false
          )
        )
        .withKey("merchant"): ReactElement)

    def categoryAutocomplete(field: FormField[Option[ExistingOrNewValue[CategoryName, UUID]]]): ReactElement =
      (ExpCategoryAutocompleteInput
        .component(
          ExpCategoryAutocompleteInput.Props(
            field = field,
            options = props.categories,
            label = "Category",
            getOptionLabel = AutocompleteLabels.categoryOptionLabel,
            selectExisting = value =>
              (value: Any) match {
                case c: GetCategories.Response.Category => Some((c.id, c.name))
                case _ => None
              },
            onChange = updateCategory,
            disabled = submit.isSubmitting,
            fullWidth = false
          )
        )
        .withKey("category"): ReactElement)

    val actionFields: ReactElement = formData match {
      case fd: BankRowRuleFormData.SetMerchant =>
        Fragment(merchantAutocomplete(fd.merchant), categoryAutocomplete(fd.category), descriptionInput(fd.description))
      case fd: BankRowRuleFormData.SetCategory =>
        Fragment(categoryAutocomplete(fd.category), descriptionInput(fd.description))
      case _: BankRowRuleFormData.Ignore => Fragment()
    }

    form(onSubmit := { e =>
      e.preventDefault()
      handleSave()
    })(
      mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "12px" })(
        mui.Typography("Create import rule").sx(new CSSPropertiesUtils { fontWeight = "700"; fontSize = "14px" }),
        submit.error match {
          case Some(msg) =>
            mui.Typography(msg).sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px" })
          case None => Fragment()
        },
        mui.TextField
          .outlined()
          .select(true)
          .label("Action")
          .size("small")
          .value(formData.actionKind.entryName)
          .disabled(submit.isSubmitting)
          .onChange(e =>
            setFormData(cur =>
              BankRowRuleFormData
                .changeKind(cur, ImportRuleActionKind.fromEntryName(e.target.asInstanceOf[dom.HTMLInputElement].value))
            )
          )
          .sx(new CSSPropertiesUtils { minWidth = "160px"; alignSelf = "flex-start" })(actionItems*),
        patternInput,
        amountInput,
        actionFields,
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "8px" })(
          (mui.Button
            .normal()(if (submit.isSubmitting) "Saving…" else "Save rule")
            .size("small")
            .variant("contained")
            .disabled(submit.isSubmitting)
            .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
            .`type`("submit")
            .build: ReactElement),
          mui.Button
            .normal()("Cancel")
            .size("small")
            .disabled(submit.isSubmitting)
            .onClick(_ => props.onCancel())
        )
      )
    )
  }
}
