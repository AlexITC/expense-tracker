package net.wiringbits.components.pages.bankstatementreview
import net.wiringbits.components.pages.AutocompleteLabels
import net.wiringbits.components.pages.importrules.ImportRuleList

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
import net.wiringbits.ui.components.inputs.{ExpCategoryAutocompleteInput, ExpMerchantAutocompleteInput}
import net.wiringbits.ui.forms.{ExpEntityRef, SubmitHooks}
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
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

  /** Parses and validates the free-text inputs with the same checks as the server, before any merchant/category is
    * resolved or created. Ignore rules carry no description, so the field is dropped for them.
    */
  def checkInputs(
      kind: ImportRuleActionKind,
      pattern: String,
      amountText: String,
      description: String
  ): Either[String, ImportRuleList.RuleInputs] = {
    val descriptionOpt =
      if (kind == ImportRuleActionKind.Ignore) None
      else Option(description.trim).filter(_.nonEmpty)
    for {
      amount <- ImportRuleList.parseAmount(amountText)
      _ <- ImportRuleMatcher.validateFields(pattern.trim, amount, descriptionOpt)
    } yield ImportRuleList.RuleInputs(amount, descriptionOpt)
  }

  /** Builds the create request once the merchant/category ids are known. For set-merchant, `categoryId` is the optional
    * override of the merchant's default category; for set-category it is the required target.
    */
  def buildRequest(
      kind: ImportRuleActionKind,
      pattern: String,
      amountText: String,
      merchantId: Option[UUID],
      categoryId: Option[UUID],
      description: String
  ): Either[String, CreateImportRule.Request] =
    for {
      inputs <- checkInputs(kind, pattern = pattern, amountText = amountText, description = description)
      action <- kind match {
        case ImportRuleActionKind.SetMerchant =>
          merchantId
            .map(mid => ImportRuleAction.SetMerchant(mid, categoryId = categoryId, description = inputs.description))
            .toRight("Merchant is required")
        case ImportRuleActionKind.SetCategory =>
          categoryId
            .map(cid => ImportRuleAction.SetCategory(cid, description = inputs.description))
            .toRight("Category is required")
        case ImportRuleActionKind.Ignore => Right(ImportRuleAction.Ignore)
      }
    } yield CreateImportRule.Request(action, pattern.trim, inputs.amount)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (formData, setFormData) = Hooks.useState(
      BankRowRuleFormData.initial(
        pattern = props.row.description,
        amountText = props.row.amount.abs.bigDecimal.toPlainString,
        description = props.row.description.trim.take(ImportRuleMatcher.MaxDescriptionLength)
      )
    )
    val submit = SubmitHooks.useSubmit()

    def saveRule(merchantId: Option[UUID], categoryId: Option[UUID]): Future[ImportRuleMatcher.Rule] =
      buildRequest(
        formData.actionKind,
        pattern = formData.pattern,
        amountText = formData.amountText,
        merchantId = merchantId,
        categoryId = categoryId,
        description = formData.description
      ) match {
        case Left(err) => Future.failed(new IllegalArgumentException(err))
        case Right(req) =>
          props.ctx.api.client
            .createImportRule(req)
            .map(resp => ImportRuleMatcher.Rule(resp.id, req.action, req.pattern, req.amount))
      }

    // Resolving a selection to an id is a side effect kept out of the form model: an existing selection is already an
    // id, a new one is matched against the loaded rows by name and only created when it is genuinely new. The created
    // value is an already-valid primitive from the field, so nothing is parsed here.
    def resolveCategory(selection: Option[ExpEntityRef[CategoryName]]): Future[Option[UUID]] =
      selection match {
        case None => Future.successful(None)
        case Some(ExpEntityRef(_, Some(id))) => Future.successful(Some(id))
        case Some(ExpEntityRef(name, None)) =>
          props.categories.find(_.name.equalsIgnoreCase(name.string)) match {
            case Some(existing) => Future.successful(Some(existing.id))
            case None =>
              props.ctx.api.client
                .createCategory(CreateCategory.Request(name = name, description = None))
                .map(resp => Some(resp.id))
          }
      }

    def resolveMerchant(
        selection: ExpEntityRef[MerchantName],
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
      checkInputs(
        formData.actionKind,
        pattern = formData.pattern,
        amountText = formData.amountText,
        description = formData.description
      ) match {
        case Left(err) => submit.fail(err)
        case Right(_) =>
          formData.actionKind match {
            case ImportRuleActionKind.Ignore =>
              submit.begin()
              saveRule(merchantId = None, categoryId = None).onComplete {
                case Success(rule) => props.onSuccess(rule, None)
                case Failure(ex) => submit.fail(ex.getMessage)
              }

            case ImportRuleActionKind.SetMerchant =>
              formData.merchant.valueOpt match {
                case None => submit.fail(formData.merchant.errorMsg.getOrElse("Invalid merchant"))
                case Some(None) => submit.fail("Merchant is required")
                case Some(Some(merchantRef)) =>
                  formData.category.valueOpt match {
                    case None => submit.fail(formData.category.errorMsg.getOrElse("Invalid category"))
                    case Some(categorySelection) =>
                      submit.begin()
                      resolveCategory(categorySelection)
                        .flatMap { categoryId =>
                          resolveMerchant(merchantRef, categoryId).flatMap { case (mid, created) =>
                            saveRule(merchantId = Some(mid), categoryId = categoryId).map(rule => (rule, created))
                          }
                        }
                        .onComplete {
                          case Success((rule, created)) => props.onSuccess(rule, created)
                          case Failure(ex) => submit.fail(ex.getMessage)
                        }
                  }
              }

            case ImportRuleActionKind.SetCategory =>
              formData.category.valueOpt match {
                case None => submit.fail(formData.category.errorMsg.getOrElse("Invalid category"))
                case Some(None) => submit.fail("Category is required")
                case Some(categoryRef @ Some(_)) =>
                  submit.begin()
                  resolveCategory(categoryRef)
                    .flatMap {
                      case None => Future.failed(new IllegalArgumentException("Category is required"))
                      case Some(cid) => saveRule(merchantId = None, categoryId = Some(cid))
                    }
                    .onComplete {
                      case Success(rule) => props.onSuccess(rule, None)
                      case Failure(ex) => submit.fail(ex.getMessage)
                    }
              }
          }
      }

    def merchantAutocomplete: ReactElement =
      (ExpMerchantAutocompleteInput
        .component(
          ExpMerchantAutocompleteInput.Props(
            field = formData.merchant,
            options = props.merchants,
            label = "Merchant",
            getOptionLabel = AutocompleteLabels.merchantOptionLabel,
            selectExisting = value =>
              (value: Any) match {
                case m: GetMerchants.Response.Merchant => Some((m.id, m.name))
                case _ => None
              },
            onChange = v => setFormData(cur => cur.copy(merchant = cur.merchant.updated(v))),
            disabled = submit.isSubmitting,
            fullWidth = false
          )
        )
        .withKey("m"): ReactElement)

    def categoryAutocomplete: ReactElement =
      (ExpCategoryAutocompleteInput
        .component(
          ExpCategoryAutocompleteInput.Props(
            field = formData.category,
            options = props.categories,
            label = "Category",
            getOptionLabel = AutocompleteLabels.categoryOptionLabel,
            selectExisting = value =>
              (value: Any) match {
                case c: GetCategories.Response.Category => Some((c.id, c.name))
                case _ => None
              },
            onChange = v => setFormData(cur => cur.copy(category = cur.category.updated(v))),
            disabled = submit.isSubmitting,
            fullWidth = false
          )
        )
        .withKey("c"): ReactElement)

    val actionItems =
      Seq(ImportRuleActionKind.SetMerchant, ImportRuleActionKind.SetCategory, ImportRuleActionKind.Ignore).map { a =>
        val label = a match {
          case ImportRuleActionKind.SetMerchant => "Set merchant"
          case ImportRuleActionKind.Ignore => "Ignore"
          case ImportRuleActionKind.SetCategory => "Set category"
        }
        mui.MenuItem.normal()(label).value(a.entryName).withKey(a.entryName).build
      }

    def descriptionField: ReactElement =
      mui.TextField
        .outlined()
        .label("Description")
        .size("small")
        .value(formData.description)
        .disabled(submit.isSubmitting)
        .helperText("Used as the notes of the created record; leave blank to use the row description")
        .onChange(e => setFormData(cur => cur.copy(description = e.target.asInstanceOf[dom.HTMLInputElement].value)))
        .withKey("d")

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
              cur.copy(actionKind =
                ImportRuleActionKind.fromEntryName(e.target.asInstanceOf[dom.HTMLInputElement].value)
              )
            )
          )
          .sx(new CSSPropertiesUtils { minWidth = "160px"; alignSelf = "flex-start" })(actionItems*),
        mui.TextField
          .outlined()
          .label("Pattern")
          .size("small")
          .value(formData.pattern)
          .disabled(submit.isSubmitting)
          .onChange(e => setFormData(cur => cur.copy(pattern = e.target.asInstanceOf[dom.HTMLInputElement].value))),
        mui.TextField
          .outlined()
          .label("Amount")
          .size("small")
          .value(formData.amountText)
          .disabled(submit.isSubmitting)
          .helperText("Rows with this amount (either sign) match; leave blank to match any amount")
          .onChange(e => setFormData(cur => cur.copy(amountText = e.target.asInstanceOf[dom.HTMLInputElement].value))),
        formData.actionKind match {
          case ImportRuleActionKind.SetMerchant =>
            Fragment(merchantAutocomplete, categoryAutocomplete, descriptionField)
          case ImportRuleActionKind.SetCategory =>
            Fragment(categoryAutocomplete, descriptionField)
          case ImportRuleActionKind.Ignore => Fragment()
        },
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
