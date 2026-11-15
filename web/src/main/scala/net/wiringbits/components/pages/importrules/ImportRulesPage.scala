package net.wiringbits.components.pages.importrules

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.{
  CreateImportRule,
  GetImportRules,
  GetMerchants,
  ImportRuleAction,
  ImportRuleActionKind,
  UpdateImportRule
}
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.components.pages.{CategoryResolver, MerchantResolver}
import net.wiringbits.components.widgets.Loader
import net.wiringbits.core.SafeHooks
import net.wiringbits.forms.BankRowRuleFormData
import net.wiringbits.models.User
import net.wiringbits.ui.components.inputs.{
  CategoryAutocompleteInput,
  MerchantAutocompleteInput,
  OptionalCategoryAutocompleteInput,
  RuleAmountInput,
  RuleDescriptionInput,
  RulePatternInput
}
import net.wiringbits.ui.forms.{ExistingOrNewValue, ExpRuleAmount, ExpRuleDescription, ExpRulePattern, SubmitHooks}
import net.wiringbits.webapp.common.validators.ValidationResult
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.compat.MuiCompat
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import net.wiringbits.webapp.utils.slinkyUtils.core.GenericHooks
import net.wiringbits.webapp.utils.slinkyUtils.forms.FormField
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, onSubmit}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object ImportRulesPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (timesRefreshing, forceRefresh) = GenericHooks.useForceRefresh

    mui.Box.sx(new CSSPropertiesUtils { padding = "24px"; maxWidth = "800px" })(
      AsyncComponent[GetImportRules.Response](
        fetch = () => props.ctx.api.client.getImportRules,
        render = rules =>
          AsyncComponent[GetMerchants.Response](
            fetch = () => props.ctx.api.client.getMerchants,
            render = merchants =>
              AsyncComponent[GetCategories.Response](
                fetch = () => props.ctx.api.client.getCategories,
                render = categories => ImportRuleList(props.ctx, rules, merchants, categories, () => forceRefresh()),
                progressIndicator = () => Loader(props.ctx),
                watchedObjects = List(timesRefreshing)
              ),
            progressIndicator = () => Loader(props.ctx),
            watchedObjects = List(timesRefreshing)
          ),
        progressIndicator = () => Loader(props.ctx),
        watchedObjects = List(timesRefreshing)
      )
    )
  }
}

object ImportRuleList {
  def apply(
      ctx: AppContext,
      rules: GetImportRules.Response,
      merchants: GetMerchants.Response,
      categories: GetCategories.Response,
      onRefresh: () => Unit
  ): KeyAddingStage =
    component(Props(ctx, rules, merchants, categories, onRefresh))

  case class Props(
      ctx: AppContext,
      rules: GetImportRules.Response,
      merchants: GetMerchants.Response,
      categories: GetCategories.Response,
      onRefresh: () => Unit
  )

  def actionLabel(r: GetImportRules.Rule): String = r.action match {
    case ImportRuleAction.Ignore => "Ignore"
    case ImportRuleAction.SetMerchant(_, Some(_), _) =>
      s"→ ${r.merchantName.getOrElse("?")} (${r.categoryName.getOrElse("?")})"
    case ImportRuleAction.SetMerchant(_, None, _) => s"→ ${r.merchantName.getOrElse("?")}"
    case ImportRuleAction.SetCategory(_, _) => s"→ ${r.categoryName.getOrElse("?")}"
  }

  def amountLabel(amount: BigDecimal): String = amount.abs.bigDecimal.toPlainString

  private val cardStyle = new CSSPropertiesUtils {
    border = "1px solid var(--lc-border)"
    borderRadius = "8px"
    backgroundColor = "var(--lc-paper)"
    marginBottom = "6px"
    padding = "12px 16px"
    width = "100%"
  }

  private val pageSize = 20

  def filterRules(rules: Seq[GetImportRules.Rule], query: String): Seq[GetImportRules.Rule] = {
    val q = query.trim.toLowerCase
    if (q.isEmpty) rules
    else
      rules.filter { r =>
        r.pattern.toLowerCase.contains(q) ||
        r.merchantName.exists(_.toLowerCase.contains(q)) ||
        r.categoryName.exists(_.toLowerCase.contains(q)) ||
        r.action.descriptionOpt.exists(_.toLowerCase.contains(q))
      }
  }

  // The validated inputs are full width, so each sits in a flexible box that keeps it readable inside the wrapping
  // field groups.
  private def fieldBox(key: String)(input: ReactElement): ReactElement =
    (mui.Box
      .sx(new CSSPropertiesUtils { flex = "1 1 200px"; minWidth = "200px" })(input)
      .withKey(key): ReactElement)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (createForm, setCreateForm) = Hooks.useState(BankRowRuleFormData.empty)
    val createSubmit = SubmitHooks.useSubmit()
    val (editing, setEditing) = Hooks.useState[Option[(UUID, BankRowRuleFormData)]](None)
    val editSubmit = SubmitHooks.useSubmit()
    val (deleteError, setDeleteError) = Hooks.useState[Option[String]](None)

    val (localMerchants, setLocalMerchants) = Hooks.useState(props.merchants.data)
    val merchantsKey = props.merchants.data.map(_.id.toString).mkString(",")
    SafeHooks.useEffect(merchantsKey)(() => { setLocalMerchants(props.merchants.data); () })

    val (localCategories, setLocalCategories) = Hooks.useState(props.categories.data)
    val categoriesKey = props.categories.data.map(_.id.toString).mkString(",")
    SafeHooks.useEffect(categoriesKey)(() => { setLocalCategories(props.categories.data); () })

    // Filter + pagination state
    val (filterText, setFilterText) = Hooks.useState("")
    val (page, setPage) = Hooks.useState(0)

    val filteredRules = filterRules(props.rules.data, filterText)

    val totalPages = Math.max(1, (filteredRules.size + pageSize - 1) / pageSize)
    val currentPage = Math.min(page, totalPages - 1)
    val pagedRules = filteredRules.slice(currentPage * pageSize, (currentPage + 1) * pageSize)

    def findOrCreateCategory(selection: ExistingOrNewValue[CategoryName, UUID]): Future[UUID] =
      CategoryResolver
        .findOrCreateCategoryReturningCreated(props.ctx.api.client, localCategories, selection)
        .map { case (id, created) =>
          created.foreach(c => setLocalCategories(cur => cur :+ c))
          id
        }

    def findOrCreateMerchant(
        selection: ExistingOrNewValue[MerchantName, UUID],
        categoryId: Option[UUID]
    ): Future[UUID] =
      MerchantResolver
        .findOrCreateMerchant(props.ctx.api.client, localMerchants, selection, categoryId)
        .map { case (id, created) =>
          created.foreach(m => setLocalMerchants(cur => cur :+ m))
          id
        }

    /** The action, pattern and amount of the request, creating the merchant/category the user typed if needed. */
    def resolveRequest(resolved: BankRowRuleFormData.Resolved): Future[(ImportRuleAction, String, Option[BigDecimal])] =
      resolved match {
        case BankRowRuleFormData.Resolved.Ignore(pattern, amount) =>
          Future.successful((ImportRuleAction.Ignore, pattern.trim, amount))
        case BankRowRuleFormData.Resolved.SetCategory(pattern, amount, description, category) =>
          findOrCreateCategory(category).map(categoryId =>
            (ImportRuleAction.SetCategory(categoryId, description = description), pattern.trim, amount)
          )
        case BankRowRuleFormData.Resolved.SetMerchant(pattern, amount, description, merchant, category) =>
          for {
            categoryId <- category.fold(Future.successful(Option.empty[UUID]))(c =>
              findOrCreateCategory(c).map(Some(_))
            )
            // The override category belongs to the rule, not the merchant, so a new merchant gets no default category.
            merchantId <- findOrCreateMerchant(merchant, categoryId = None)
          } yield (
            ImportRuleAction.SetMerchant(merchantId, categoryId = categoryId, description = description),
            pattern.trim,
            amount
          )
      }

    def save(
        formData: BankRowRuleFormData,
        submit: SubmitHooks.Control,
        send: (ImportRuleAction, String, Option[BigDecimal]) => Future[Unit]
    )(onSaved: () => Unit): Unit =
      formData.submitRequest match {
        case None =>
          submit.fail(formData.formValidationErrors.headOption.getOrElse("Please fix the highlighted fields"))
        case Some(resolved) =>
          submit.begin()
          resolveRequest(resolved)
            .flatMap(send.tupled)
            .onComplete {
              case Success(_) => onSaved(); props.onRefresh()
              case Failure(ex) => submit.fail(ex.getMessage)
            }
      }

    def handleCreate(): Unit =
      save(
        createForm,
        createSubmit,
        (action, pattern, amount) =>
          props.ctx.api.client.createImportRule(CreateImportRule.Request(action, pattern, amount)).map(_ => ())
      ) { () =>
        createSubmit.reset()
        setCreateForm(cur => BankRowRuleFormData.changeKind(BankRowRuleFormData.empty, cur.actionKind))
      }

    def handleUpdate(ruleId: UUID, formData: BankRowRuleFormData): Unit =
      save(
        formData,
        editSubmit,
        (action, pattern, amount) =>
          props.ctx.api.client
            .updateImportRule(ruleId, UpdateImportRule.Request(action, pattern, amount))
            .map(_ => ())
      ) { () =>
        editSubmit.reset()
        setEditing(None)
      }

    def handleDelete(ruleId: UUID): Unit =
      props.ctx.api.client
        .deleteImportRule(ruleId)
        .onComplete {
          case Success(_) => setDeleteError(None); props.onRefresh()
          case Failure(ex) => setDeleteError(Some(ex.getMessage))
        }

    type Update = (BankRowRuleFormData => BankRowRuleFormData) => Unit

    val updateCreate: Update = f => setCreateForm(cur => f(cur))
    val updateEdit: Update = f => setEditing(cur => cur.map { case (id, fd) => (id, f(fd)) })

    def actionSelector(current: ImportRuleActionKind, disabled: Boolean, update: Update): ReactElement = {
      val items = Seq(ImportRuleActionKind.SetMerchant, ImportRuleActionKind.SetCategory, ImportRuleActionKind.Ignore)
        .map { a =>
          val label = a match {
            case ImportRuleActionKind.SetMerchant => "Set merchant"
            case ImportRuleActionKind.Ignore => "Ignore"
            case ImportRuleActionKind.SetCategory => "Set category"
          }
          mui.MenuItem.normal()(label).value(a.entryName).withKey(a.entryName).build
        }
      mui.TextField
        .outlined()
        .select(true)
        .label("Action")
        .value(current.entryName)
        .size("small")
        .disabled(disabled)
        .onChange { e =>
          val kind = ImportRuleActionKind.fromEntryName(e.target.asInstanceOf[dom.HTMLInputElement].value)
          update(cur => BankRowRuleFormData.changeKind(cur, kind))
        }
        .sx(new CSSPropertiesUtils { minWidth = "140px" })(items*)
    }

    /** What the rule sets once it matches: the merchant/category target and the optional description. Empty for an
      * Ignore rule, which only matches.
      */
    def setFields(formData: BankRowRuleFormData, disabled: Boolean, update: Update): Seq[ReactElement] = {
      def updateDescription(value: ValidationResult[Option[ExpRuleDescription]]): Unit =
        update {
          case fd: BankRowRuleFormData.SetMerchant => fd.copy(description = fd.description.updated(value))
          case fd: BankRowRuleFormData.SetCategory => fd.copy(description = fd.description.updated(value))
          case other => other
        }

      def descriptionInput(field: FormField[Option[ExpRuleDescription]]): ReactElement =
        fieldBox("description")(
          RuleDescriptionInput.component(
            RuleDescriptionInput.Props(field = field, disabled = disabled, onChange = updateDescription)
          )
        )

      formData match {
        case fd: BankRowRuleFormData.SetMerchant =>
          Seq(
            fieldBox("merchant")(
              MerchantAutocompleteInput.component(
                MerchantAutocompleteInput.Props(
                  field = fd.merchant,
                  options = localMerchants,
                  label = "Merchant",
                  onChange = value =>
                    update {
                      case cur: BankRowRuleFormData.SetMerchant => cur.copy(merchant = cur.merchant.updated(value))
                      case other => other
                    },
                  disabled = disabled
                )
              )
            ),
            fieldBox("override-category")(
              OptionalCategoryAutocompleteInput.component(
                OptionalCategoryAutocompleteInput.Props(
                  field = fd.category,
                  options = localCategories,
                  label = "Category (optional)",
                  onChange = value =>
                    update {
                      case cur: BankRowRuleFormData.SetMerchant => cur.copy(category = cur.category.updated(value))
                      case other => other
                    },
                  disabled = disabled
                )
              )
            ),
            descriptionInput(fd.description)
          )
        case fd: BankRowRuleFormData.SetCategory =>
          Seq(
            fieldBox("category")(
              CategoryAutocompleteInput.component(
                CategoryAutocompleteInput.Props(
                  field = fd.category,
                  options = localCategories,
                  label = "Category",
                  onChange = value =>
                    update {
                      case cur: BankRowRuleFormData.SetCategory => cur.copy(category = cur.category.updated(value))
                      case other => other
                    },
                  disabled = disabled
                )
              )
            ),
            descriptionInput(fd.description)
          )
        case _: BankRowRuleFormData.Ignore => Seq.empty
      }
    }

    def fieldGroup(label: String, key: String, fields: Seq[ReactElement]): ReactElement =
      (mui.Box
        .sx(new CSSPropertiesUtils {
          display = "flex"; flexDirection = "column"; gap = "6px"
          border = "1px solid var(--lc-border)"; borderRadius = "8px"; padding = "10px 12px"
        })(
          mui
            .Typography(label)
            .sx(new CSSPropertiesUtils {
              fontSize = "11px"; fontWeight = "600"; letterSpacing = "0.06em"
              textTransform = "uppercase"; color = "var(--lc-text-2)"
            }),
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"; flexWrap = "wrap"; alignItems = "center"; gap = "12px"
          })(Fragment(fields*))
        )
        .withKey(key): ReactElement)

    /** The pattern and amount are match criteria; the merchant/category/description are what the rule sets. Grouping
      * and labelling them makes it clear which fields decide a match and which change the row.
      */
    def ruleFormBody(formData: BankRowRuleFormData, disabled: Boolean, update: Update): Seq[ReactElement] = {
      val patternInput = fieldBox("pattern")(
        RulePatternInput.component(
          RulePatternInput.Props(
            field = formData.pattern,
            disabled = disabled,
            onChange =
              (value: ValidationResult[ExpRulePattern]) => update(cur => cur.withPattern(cur.pattern.updated(value)))
          )
        )
      )
      val amountInput = fieldBox("amount")(
        RuleAmountInput.component(
          RuleAmountInput.Props(
            field = formData.amount,
            disabled = disabled,
            onChange = (value: ValidationResult[Option[ExpRuleAmount]]) =>
              update(cur => cur.withAmount(cur.amount.updated(value)))
          )
        )
      )
      val matchGroup = fieldGroup(label = "Matches on", key = "match-group", fields = Seq(patternInput, amountInput))
      val sets = setFields(formData, disabled, update)
      if (sets.isEmpty) Seq(matchGroup)
      else Seq(matchGroup, fieldGroup(label = "Sets", key = "set-group", fields = sets))
    }

    def submitError(submit: SubmitHooks.Control): ReactElement =
      submit.error match {
        case Some(msg) =>
          mui
            .Typography(msg)
            .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px"; width = "100%" })
        case None => Fragment()
      }

    val createFormView = form(onSubmit := { e =>
      e.preventDefault()
      handleCreate()
    })(
      mui.Box.sx(new CSSPropertiesUtils {
        border = "1px solid var(--lc-brand)"
        borderRadius = "8px"
        padding = "16px"
        backgroundColor = "var(--lc-paper)"
        marginBottom = "16px"
        display = "flex"
        flexWrap = "wrap"
        alignItems = "center"
        gap = "12px"
      })(
        actionSelector(createForm.actionKind, createSubmit.isSubmitting, updateCreate),
        Fragment(ruleFormBody(createForm, createSubmit.isSubmitting, updateCreate)*),
        mui.Button
          .normal()(if (createSubmit.isSubmitting) "Adding…" else "Add rule")
          .variant("contained")
          .disabled(createSubmit.buttonDisabled(createForm.isValid))
          .`type`("submit")
          .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)"; borderRadius = "8px" }),
        submitError(createSubmit)
      )
    )

    // Filter bar + pagination controls
    val filterBar: ReactElement =
      mui.Box.sx(new CSSPropertiesUtils {
        display = "flex"; alignItems = "center"; gap = "12px"; marginBottom = "8px"
      })(
        mui.TextField
          .outlined()
          .label("Filter rules")
          .value(filterText)
          .size("small")
          .onChange { e =>
            val v = e.target.asInstanceOf[dom.HTMLInputElement].value
            setFilterText(v)
            setPage(0)
          }
          .sx(new CSSPropertiesUtils { minWidth = "240px" })(),
        mui
          .Typography(s"${filteredRules.size} rule(s)")
          .sx(new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text-2)" })
      )

    val paginationControls: ReactElement =
      mui.Box.sx(new CSSPropertiesUtils { marginTop = "8px" })(
        mui.Pagination
          .withProps(MuiCompat.paginationProps(count = totalPages, page = currentPage, onChange = setPage))
      )

    def editFormView(ruleId: UUID, formData: BankRowRuleFormData): ReactElement =
      form(onSubmit := { e =>
        e.preventDefault()
        handleUpdate(ruleId, formData)
      })(
        mui.Box.sx(new CSSPropertiesUtils {
          display = "flex"
          flexWrap = "wrap"
          alignItems = "center"
          gap = "12px"
          padding = "4px 0"
        })(
          actionSelector(formData.actionKind, editSubmit.isSubmitting, updateEdit),
          Fragment(ruleFormBody(formData, editSubmit.isSubmitting, updateEdit)*),
          mui.Button
            .normal()(if (editSubmit.isSubmitting) "Saving…" else "Save")
            .size("small")
            .variant("contained")
            .disabled(editSubmit.buttonDisabled(formData.isValid))
            .`type`("submit")
            .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" }),
          mui.Button
            .normal()("Cancel")
            .size("small")
            .disabled(editSubmit.isSubmitting)
            .onClick { _ =>
              editSubmit.reset()
              setEditing(None)
            },
          submitError(editSubmit)
        )
      )

    val ruleItems: Seq[ReactElement] = pagedRules.map { r =>
      val content: ReactElement = editing match {
        case Some((id, formData)) if id == r.id => editFormView(id, formData)
        case _ =>
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"
            alignItems = "center"
            justifyContent = "space-between"
            width = "100%"
          })(
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "2px" })(
              mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "6px" })(
                mui
                  .Typography(r.pattern)
                  .sx(new CSSPropertiesUtils {
                    fontFamily = "\"IBM Plex Mono\", monospace"
                    fontSize = "13px"
                    fontWeight = "600"
                    color = "var(--lc-text)"
                  }),
                r.amount match {
                  case Some(a) =>
                    mui.Chip
                      .label(amountLabel(a))
                      .size("small")
                      .sx(new CSSPropertiesUtils {
                        height = "18px"; fontSize = "11px"
                        fontFamily = "\"IBM Plex Mono\", monospace"
                      })
                  case None => Fragment()
                },
                if (r.autoCreated)
                  mui.Chip
                    .label("auto")
                    .size("small")
                    .sx(new CSSPropertiesUtils {
                      height = "18px"; fontSize = "11px"
                      backgroundColor = "var(--lc-tint)"; color = "var(--lc-brand-dark)"
                    })
                else Fragment()
              ),
              mui
                .Typography(actionLabel(r))
                .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" }),
              r.action.descriptionOpt match {
                case Some(d) =>
                  mui
                    .Typography(d)
                    .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)"; fontStyle = "italic" })
                case None => Fragment()
              }
            ),
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "4px" })(
              mui.Button
                .normal()("Edit")
                .size("small")
                .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)" })
                .onClick { _ =>
                  editSubmit.reset()
                  setEditing(Some((r.id, BankRowRuleFormData.fromRule(r))))
                },
              mui.Button
                .normal()("Delete")
                .size("small")
                .sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
                .onClick(_ => handleDelete(r.id))
            )
          )
      }

      mui.Box.sx(cardStyle)(content).withKey(r.id.toString)
    }

    Fragment(
      createFormView,
      deleteError match {
        case Some(msg) =>
          mui
            .Typography(msg)
            .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px"; marginBottom = "8px" })
        case None => Fragment()
      },
      filterBar,
      Fragment(ruleItems*),
      paginationControls
    )
  }
}
