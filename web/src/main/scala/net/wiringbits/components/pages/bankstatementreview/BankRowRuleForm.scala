package net.wiringbits.components.pages.bankstatementreview
import net.wiringbits.components.pages.{AutocompleteLabels, CategoryResolver}
import net.wiringbits.components.pages.importrules.ImportRuleList

import com.olvind.mui.muiMaterial.autocompleteAutocompleteMod.AutocompleteRenderInputParams
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.{
  CreateImportRule,
  CreateMerchant,
  GetMerchants,
  ImportRuleAction,
  ImportRuleActionKind
}
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.ui.forms.SubmitHooks
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.compat.MuiCompat
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

  private def renderTextField(label: String)(params: AutocompleteRenderInputParams): ReactElement =
    (mui.TextField.outlined
      .withProps(MuiCompat.textFieldProps(params, label, size = Some("small")))
      .withKey("tf"): ReactElement)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (action, setAction) = Hooks.useState(ImportRuleActionKind.SetMerchant: ImportRuleActionKind)
    val (pattern, setPattern) = Hooks.useState(props.row.description)
    val (amountText, setAmountText) = Hooks.useState(props.row.amount.bigDecimal.toPlainString)
    val (description, setDescription) =
      Hooks.useState(props.row.description.trim.take(ImportRuleMatcher.MaxDescriptionLength))
    val (merchantInput, setMerchantInput) = Hooks.useState("")
    val (merchantIdOpt, setMerchantIdOpt) = Hooks.useState[Option[UUID]](None)
    val (categoryInput, setCategoryInput) = Hooks.useState("")
    val (categoryIdOpt, setCategoryIdOpt) = Hooks.useState[Option[UUID]](None)
    val submit = SubmitHooks.useSubmit()

    def resolveCategoryId(catName: String): Future[UUID] =
      CategoryResolver
        .resolveOrCreateCategory(props.ctx.api.client, props.categories, categoryIdOpt, catName)
        .map(_._1)

    def saveRule(
        merchantId: Option[UUID],
        categoryId: Option[UUID]
    ): Future[ImportRuleMatcher.Rule] =
      buildRequest(
        action,
        pattern = pattern,
        amountText = amountText,
        merchantId = merchantId,
        categoryId = categoryId,
        description = description
      ) match {
        case Left(err) => Future.failed(new IllegalArgumentException(err))
        case Right(req) =>
          props.ctx.api.client
            .createImportRule(req)
            .map(resp => ImportRuleMatcher.Rule(resp.id, req.action, req.pattern, req.amount))
      }

    def handleSave(): Unit = {
      checkInputs(action, pattern = pattern, amountText = amountText, description = description) match {
        case Left(err) => submit.fail(err)
        case Right(_) =>
          action match {
            case ImportRuleActionKind.Ignore =>
              submit.begin()
              saveRule(merchantId = None, categoryId = None).onComplete {
                case Success(rule) => props.onSuccess(rule, None)
                case Failure(ex) => submit.fail(ex.getMessage)
              }

            case ImportRuleActionKind.SetMerchant =>
              val merchantName = merchantInput.trim
              if (merchantName.isEmpty) {
                submit.fail("Merchant is required")
              } else {
                submit.begin()
                val catName = categoryInput.trim
                val fCategoryIdOpt: Future[Option[UUID]] =
                  if (categoryIdOpt.isEmpty && catName.isEmpty) Future.successful(None)
                  else resolveCategoryId(catName).map(Some(_))
                fCategoryIdOpt
                  .flatMap { resolvedCatId =>
                    val fMerchantAndNew: Future[(UUID, Option[GetMerchants.Response.Merchant])] =
                      merchantIdOpt match {
                        case Some(id) => Future.successful((id, None))
                        case None =>
                          props.merchants.find(_.name.equalsIgnoreCase(merchantName)) match {
                            case Some(existing) => Future.successful((existing.id, None))
                            case None =>
                              props.ctx.api.client
                                .createMerchant(
                                  CreateMerchant
                                    .Request(
                                      name = merchantName,
                                      notes = None,
                                      categoryId = resolvedCatId,
                                      taxId = None
                                    )
                                )
                                .map { resp =>
                                  val newM = GetMerchants.Response.Merchant(
                                    id = resp.id,
                                    name = resp.name,
                                    notes = None,
                                    categoryId = resolvedCatId,
                                    taxId = None,
                                    reviewed = false,
                                    createdAt = Instant.now()
                                  )
                                  (resp.id, Some(newM))
                                }
                          }
                      }
                    fMerchantAndNew.flatMap { case (mid, newMerchantOpt) =>
                      saveRule(merchantId = Some(mid), categoryId = resolvedCatId).map(rule => (rule, newMerchantOpt))
                    }
                  }
                  .onComplete {
                    case Success((rule, newMerchantOpt)) => props.onSuccess(rule, newMerchantOpt)
                    case Failure(ex) => submit.fail(ex.getMessage)
                  }
              }

            case ImportRuleActionKind.SetCategory =>
              val catName = categoryInput.trim
              if (catName.isEmpty) {
                submit.fail("Category is required")
              } else {
                submit.begin()
                resolveCategoryId(catName)
                  .flatMap(cid => saveRule(merchantId = None, categoryId = Some(cid)))
                  .onComplete {
                    case Success(rule) => props.onSuccess(rule, None)
                    case Failure(ex) => submit.fail(ex.getMessage)
                  }
              }
          }
      }
    }

    val acProps = MuiCompat.freeSoloAutocompleteProps(
      options = props.merchants,
      renderInput = renderTextField("Merchant"),
      inputValue = merchantInput,
      getOptionLabel = AutocompleteLabels.merchantOptionLabel,
      onSelect = value =>
        (value: Any) match {
          case m: GetMerchants.Response.Merchant => setMerchantIdOpt(Some(m.id)); setMerchantInput(m.name)
          case _ => ()
        },
      onInput = (text, rsn) => {
        setMerchantInput(text)
        if (rsn != "reset") setMerchantIdOpt(None)
      },
      disabled = submit.isSubmitting,
      fullWidth = false
    )

    val catAcProps = MuiCompat.freeSoloAutocompleteProps(
      options = props.categories,
      renderInput = renderTextField("Category"),
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
      disabled = submit.isSubmitting,
      fullWidth = false
    )

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
        .value(description)
        .disabled(submit.isSubmitting)
        .helperText("Used as the notes of the created record; leave blank to use the row description")
        .onChange(e => setDescription(e.target.asInstanceOf[dom.HTMLInputElement].value))
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
          .value(action.entryName)
          .disabled(submit.isSubmitting)
          .onChange(e =>
            setAction(ImportRuleActionKind.fromEntryName(e.target.asInstanceOf[dom.HTMLInputElement].value))
          )
          .sx(new CSSPropertiesUtils { minWidth = "160px"; alignSelf = "flex-start" })(actionItems*),
        mui.TextField
          .outlined()
          .label("Pattern")
          .size("small")
          .value(pattern)
          .disabled(submit.isSubmitting)
          .onChange(e => setPattern(e.target.asInstanceOf[dom.HTMLInputElement].value)),
        mui.TextField
          .outlined()
          .label("Amount")
          .size("small")
          .value(amountText)
          .disabled(submit.isSubmitting)
          .helperText("Rows must have exactly this signed amount; leave blank to match any amount")
          .onChange(e => setAmountText(e.target.asInstanceOf[dom.HTMLInputElement].value)),
        action match {
          case ImportRuleActionKind.SetMerchant =>
            Fragment(
              (mui.Autocomplete.withProps(acProps).withKey("m"): ReactElement),
              (mui.Autocomplete.withProps(catAcProps).withKey("c"): ReactElement),
              descriptionField
            )
          case ImportRuleActionKind.SetCategory =>
            Fragment((mui.Autocomplete.withProps(catAcProps).withKey("c"): ReactElement), descriptionField)
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
