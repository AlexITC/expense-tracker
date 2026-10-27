package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.autocompleteAutocompleteMod.{AutocompleteProps, AutocompleteRenderInputParams}
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.api.models.categories.{CreateCategory, GetCategories}
import net.wiringbits.api.models.merchants.{CreateImportRule, CreateMerchant, GetMerchants, ImportRuleAction}
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.compat.MuiCompat
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, onSubmit}

import java.time.Instant
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
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

  private def renderTextField(label: String)(params: AutocompleteRenderInputParams): ReactElement =
    (mui.TextField.outlined
      .withProps(MuiCompat.textFieldProps(params, label, size = Some("small")))
      .withKey("tf"): ReactElement)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (action, setAction) = Hooks.useState(ImportRuleAction.SetMerchant: ImportRuleAction)
    val (pattern, setPattern) = Hooks.useState(props.row.description)
    val (merchantInput, setMerchantInput) = Hooks.useState("")
    val (merchantIdOpt, setMerchantIdOpt) = Hooks.useState[Option[UUID]](None)
    val (categoryInput, setCategoryInput) = Hooks.useState("")
    val (categoryIdOpt, setCategoryIdOpt) = Hooks.useState[Option[UUID]](None)
    val (saving, setSaving) = Hooks.useState(false)
    val (error, setError) = Hooks.useState("")

    // Picked id, else an existing category by case-insensitive name, else a newly created one.
    def resolveCategoryId(catName: String): Future[UUID] =
      categoryIdOpt match {
        case Some(id) => Future.successful(id)
        case None =>
          props.categories.find(_.name.equalsIgnoreCase(catName)) match {
            case Some(existing) => Future.successful(existing.id)
            case None =>
              props.ctx.api.client
                .createCategory(CreateCategory.Request(name = catName))
                .map(_.id)
          }
      }

    def handleSave(): Unit = {
      val trimmedPattern = pattern.trim
      if (trimmedPattern.isEmpty) {
        setError("Pattern is required")
      } else {
        action match {
          case ImportRuleAction.Ignore =>
            setSaving(true)
            props.ctx.api.client
              .createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, trimmedPattern, None))
              .onComplete {
                case Success(resp) =>
                  props.onSuccess(ImportRuleMatcher.Rule(resp.id, ImportRuleAction.Ignore, trimmedPattern, None), None)
                case Failure(ex) =>
                  setError(ex.getMessage)
                  setSaving(false)
              }

          case ImportRuleAction.SetMerchant =>
            val merchantName = merchantInput.trim
            if (merchantName.isEmpty) {
              setError("Merchant is required")
            } else {
              setSaving(true)
              val catName = categoryInput.trim
              val fCategoryIdOpt: Future[Option[UUID]] =
                if (categoryIdOpt.isEmpty && catName.isEmpty) Future.successful(None)
                else resolveCategoryId(catName).map(Some(_))
              val fMerchantAndNew: Future[(UUID, Option[GetMerchants.Response.Merchant])] =
                fCategoryIdOpt.flatMap { resolvedCatId =>
                  merchantIdOpt match {
                    case Some(id) => Future.successful((id, None))
                    case None =>
                      props.merchants.find(_.name.equalsIgnoreCase(merchantName)) match {
                        case Some(existing) => Future.successful((existing.id, None))
                        case None =>
                          props.ctx.api.client
                            .createMerchant(
                              CreateMerchant
                                .Request(name = merchantName, notes = None, categoryId = resolvedCatId, taxId = None)
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
                }
              fMerchantAndNew
                .flatMap { case (mid, newMerchantOpt) =>
                  props.ctx.api.client
                    .createImportRule(
                      CreateImportRule.Request(ImportRuleAction.SetMerchant, trimmedPattern, Some(mid))
                    )
                    .map(resp =>
                      (
                        ImportRuleMatcher.Rule(resp.id, ImportRuleAction.SetMerchant, trimmedPattern, Some(mid)),
                        newMerchantOpt
                      )
                    )
                }
                .onComplete {
                  case Success((rule, newMerchantOpt)) => props.onSuccess(rule, newMerchantOpt)
                  case Failure(ex) => setError(ex.getMessage); setSaving(false)
                }
            }

          case ImportRuleAction.SetCategory =>
            val catName = categoryInput.trim
            if (catName.isEmpty) {
              setError("Category is required")
            } else {
              setSaving(true)
              resolveCategoryId(catName)
                .flatMap { cid =>
                  props.ctx.api.client
                    .createImportRule(
                      CreateImportRule.Request(ImportRuleAction.SetCategory, trimmedPattern, None, Some(cid))
                    )
                    .map(resp =>
                      ImportRuleMatcher.Rule(resp.id, ImportRuleAction.SetCategory, trimmedPattern, None, Some(cid))
                    )
                }
                .onComplete {
                  case Success(rule) => props.onSuccess(rule, None)
                  case Failure(ex) => setError(ex.getMessage); setSaving(false)
                }
            }
        }
      }
    }

    val acProps = AutocompleteProps[GetMerchants.Response.Merchant, Boolean, Boolean, Boolean, "div"](
      options = props.merchants.toJSArray,
      renderInput = renderTextField("Merchant")
    )
    val acDyn = acProps.asInstanceOf[js.Dynamic]
    acDyn.freeSolo = true
    acDyn.size = "small"
    acDyn.disabled = saving
    acDyn.inputValue = merchantInput
    acDyn.getOptionLabel = (opt: js.Any) => DescriptionRow.merchantOptionLabel(opt)
    acDyn.onChange = (_: js.Any, value: js.Any, _: js.Any, _: js.Any) =>
      (value: Any) match {
        case m: GetMerchants.Response.Merchant => setMerchantIdOpt(Some(m.id)); setMerchantInput(m.name)
        case _ => ()
      }
    acDyn.onInputChange = (_: js.Any, value: js.Any, reason: js.Any) => {
      val (text, rsn) = MuiCompat.autocompleteInputChange(value, reason)
      setMerchantInput(text)
      if (rsn != "reset") setMerchantIdOpt(None)
    }

    val catAcProps = AutocompleteProps[GetCategories.Response.Category, Boolean, Boolean, Boolean, "div"](
      options = props.categories.toJSArray,
      renderInput = renderTextField("Category")
    )
    val catDyn = catAcProps.asInstanceOf[js.Dynamic]
    catDyn.freeSolo = true
    catDyn.size = "small"
    catDyn.disabled = saving
    catDyn.inputValue = categoryInput
    catDyn.getOptionLabel = (opt: js.Any) => DescriptionRow.categoryOptionLabel(opt)
    catDyn.onChange = (_: js.Any, value: js.Any, _: js.Any, _: js.Any) =>
      (value: Any) match {
        case c: GetCategories.Response.Category => setCategoryIdOpt(Some(c.id)); setCategoryInput(c.name)
        case _ => ()
      }
    catDyn.onInputChange = (_: js.Any, value: js.Any, reason: js.Any) => {
      val (text, rsn) = MuiCompat.autocompleteInputChange(value, reason)
      setCategoryInput(text)
      if (rsn != "reset") setCategoryIdOpt(None)
    }

    val actionItems = Seq(ImportRuleAction.SetMerchant, ImportRuleAction.SetCategory, ImportRuleAction.Ignore).map {
      a =>
        val label = a match {
          case ImportRuleAction.SetMerchant => "Set merchant"
          case ImportRuleAction.Ignore => "Ignore"
          case ImportRuleAction.SetCategory => "Set category"
        }
        mui.MenuItem.normal()(label).value(a.entryName).withKey(a.entryName).build
    }

    form(onSubmit := { e =>
      e.preventDefault()
      handleSave()
    })(
      mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "12px" })(
        mui.Typography("Create import rule").sx(new CSSPropertiesUtils { fontWeight = "700"; fontSize = "14px" }),
        if (error.nonEmpty)
          mui.Typography(error).sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px" })
        else Fragment(),
        mui.TextField
          .outlined()
          .select(true)
          .label("Action")
          .size("small")
          .value(action.entryName)
          .disabled(saving)
          .onChange(e => setAction(ImportRuleAction.fromEntryName(e.target.asInstanceOf[dom.HTMLInputElement].value)))
          .sx(new CSSPropertiesUtils { minWidth = "160px"; alignSelf = "flex-start" })(actionItems*),
        mui.TextField
          .outlined()
          .label("Pattern")
          .size("small")
          .value(pattern)
          .disabled(saving)
          .onChange(e => setPattern(e.target.asInstanceOf[dom.HTMLInputElement].value)),
        action match {
          case ImportRuleAction.SetMerchant =>
            Fragment(
              (mui.Autocomplete.withProps(acProps).withKey("m"): ReactElement),
              (mui.Autocomplete.withProps(catAcProps).withKey("c"): ReactElement)
            )
          case ImportRuleAction.SetCategory =>
            (mui.Autocomplete.withProps(catAcProps).withKey("c"): ReactElement)
          case ImportRuleAction.Ignore => Fragment()
        },
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "8px" })(
          (mui.Button
            .normal()(if (saving) "Saving…" else "Save rule")
            .size("small")
            .variant("contained")
            .disabled(saving)
            .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
            .`type`("submit")
            .build: ReactElement),
          mui.Button
            .normal()("Cancel")
            .size("small")
            .disabled(saving)
            .onClick(_ => props.onCancel())
        )
      )
    )
  }
}
