package net.wiringbits.components.pages.importrules
import net.wiringbits.components.pages.{AutocompleteLabels, CategoryResolver}

import com.olvind.mui.muiMaterial.autocompleteAutocompleteMod.AutocompleteRenderInputParams
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.components.widgets.Loader
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.api.models.merchants.{
  CreateImportRule,
  CreateMerchant,
  GetImportRules,
  GetMerchants,
  ImportRuleAction,
  ImportRuleActionKind,
  UpdateImportRule
}
import net.wiringbits.core.SafeHooks
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.compat.MuiCompat
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import net.wiringbits.webapp.utils.slinkyUtils.core.GenericHooks
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, onSubmit}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

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

  /** The raw inputs of the create form or the inline edit form.
    *
    * `categoryInput`/`categoryId` are the set_category target; `overrideCategoryInput`/`overrideCategoryId` are the
    * optional category a set_merchant rule uses instead of the merchant's default.
    */
  case class RuleForm(
      kind: ImportRuleActionKind,
      pattern: String,
      amount: String,
      merchantInput: String,
      merchantId: Option[UUID],
      categoryInput: String,
      categoryId: Option[UUID],
      overrideCategoryInput: String,
      overrideCategoryId: Option[UUID],
      description: String
  )

  object RuleForm {
    val empty: RuleForm = RuleForm(
      kind = ImportRuleActionKind.SetMerchant,
      pattern = "",
      amount = "",
      merchantInput = "",
      merchantId = None,
      categoryInput = "",
      categoryId = None,
      overrideCategoryInput = "",
      overrideCategoryId = None,
      description = ""
    )

    /** The edit form prefilled from a stored rule. `categoryName` is the set_category target or, for set_merchant, the
      * override category.
      */
    def fromRule(r: GetImportRules.Rule): RuleForm = {
      val categoryName = r.categoryName.getOrElse("")
      RuleForm(
        kind = r.action.kind,
        pattern = r.pattern,
        amount = r.amount.fold("")(_.bigDecimal.toPlainString),
        merchantInput = r.merchantName.getOrElse(""),
        merchantId = r.action.merchantIdOpt,
        categoryInput = if (r.action.categoryIdOpt.isDefined) categoryName else "",
        categoryId = r.action.categoryIdOpt,
        overrideCategoryInput = if (r.action.overrideCategoryIdOpt.isDefined) categoryName else "",
        overrideCategoryId = r.action.overrideCategoryIdOpt,
        description = r.action.descriptionOpt.getOrElse("")
      )
    }
  }

  /** The validated amount and description of a form, ready to build the request. */
  case class RuleInputs(amount: Option[BigDecimal], description: Option[String])

  /** Blank means no amount; anything else must parse as a number. */
  def parseAmount(text: String): Either[String, Option[BigDecimal]] = {
    val trimmed = text.trim
    if (trimmed.isEmpty) Right(None)
    else Try(BigDecimal(trimmed)).toOption.map(Some(_)).toRight("Amount must be a number, e.g. -12.50")
  }

  /** Runs the same checks as the server so errors show before any merchant/category is created. Ignore rules carry no
    * description, so the field is dropped for them.
    */
  def checkInputs(form: RuleForm): Either[String, RuleInputs] = {
    val description =
      if (form.kind == ImportRuleActionKind.Ignore) None
      else Option(form.description.trim).filter(_.nonEmpty)
    for {
      amount <- parseAmount(form.amount)
      _ <- form.kind match {
        case ImportRuleActionKind.SetMerchant if form.merchantInput.trim.isEmpty => Left("Enter a merchant name")
        case ImportRuleActionKind.SetCategory if form.categoryInput.trim.isEmpty => Left("Enter a category name")
        case _ => Right(())
      }
      _ <- ImportRuleMatcher.validateFields(form.pattern, amount, description)
    } yield RuleInputs(amount, description)
  }

  def actionLabel(r: GetImportRules.Rule): String = r.action match {
    case ImportRuleAction.Ignore => "Ignore"
    case ImportRuleAction.SetMerchant(_, Some(_), _) =>
      s"→ ${r.merchantName.getOrElse("?")} (${r.categoryName.getOrElse("?")})"
    case ImportRuleAction.SetMerchant(_, None, _) => s"→ ${r.merchantName.getOrElse("?")}"
    case ImportRuleAction.SetCategory(_, _) => s"→ ${r.categoryName.getOrElse("?")}"
  }

  def amountLabel(amount: BigDecimal): String = amount.bigDecimal.toPlainString

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

  private def renderMerchantTextField(params: AutocompleteRenderInputParams): ReactElement =
    (mui.TextField.outlined
      .withProps(MuiCompat.textFieldProps(params, "Merchant", size = Some("small")))
      .withKey("tf"): ReactElement)

  private def renderCategoryTextField(params: AutocompleteRenderInputParams): ReactElement =
    (mui.TextField.outlined
      .withProps(MuiCompat.textFieldProps(params, "Category", size = Some("small")))
      .withKey("tf"): ReactElement)

  private def renderOverrideCategoryTextField(params: AutocompleteRenderInputParams): ReactElement =
    (mui.TextField.outlined
      .withProps(MuiCompat.textFieldProps(params, "Category (optional)", size = Some("small")))
      .withKey("tf"): ReactElement)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (newForm, setNewForm) = Hooks.useState(RuleForm.empty)
    val (error, setError) = Hooks.useState("")
    val (editingId, setEditingId) = Hooks.useState[Option[UUID]](None)
    val (editForm, setEditForm) = Hooks.useState(RuleForm.empty)

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

    def resolveOrCreateMerchant(
        merchantInput: String,
        merchantIdOpt: Option[UUID]
    ): Future[UUID] =
      merchantIdOpt match {
        case Some(id) => Future.successful(id)
        case None =>
          val name = merchantInput.trim
          localMerchants.find(_.name.equalsIgnoreCase(name)) match {
            case Some(existing) => Future.successful(existing.id)
            case None =>
              props.ctx.api.client
                .createMerchant(CreateMerchant.Request(name, notes = None, None, taxId = None))
                .map { resp =>
                  setLocalMerchants(cur =>
                    cur :+ GetMerchants.Response
                      .Merchant(
                        resp.id,
                        resp.name,
                        notes = None,
                        None,
                        taxId = None,
                        reviewed = false,
                        java.time.Instant.now()
                      )
                  )
                  resp.id
                }
          }
      }

    def resolveOrCreateCategory(
        categoryInput: String,
        categoryIdOpt: Option[UUID]
    ): Future[UUID] =
      CategoryResolver
        .resolveOrCreateCategory(props.ctx.api.client, localCategories, categoryIdOpt, categoryInput)
        .map { case (id, newCategoryOpt) =>
          newCategoryOpt.foreach(c => setLocalCategories(cur => cur :+ c))
          id
        }

    def resolveAction(form: RuleForm, description: Option[String]): Future[ImportRuleAction] =
      form.kind match {
        case ImportRuleActionKind.Ignore => Future.successful(ImportRuleAction.Ignore)
        case ImportRuleActionKind.SetMerchant =>
          for {
            mid <- resolveOrCreateMerchant(form.merchantInput, form.merchantId)
            overrideCategoryId <-
              if (form.overrideCategoryInput.trim.isEmpty) Future.successful(None)
              else resolveOrCreateCategory(form.overrideCategoryInput, form.overrideCategoryId).map(Some(_))
          } yield ImportRuleAction.SetMerchant(mid, categoryId = overrideCategoryId, description = description)
        case ImportRuleActionKind.SetCategory =>
          resolveOrCreateCategory(form.categoryInput, form.categoryId)
            .map(cid => ImportRuleAction.SetCategory(cid, description = description))
      }

    def submit(form: RuleForm, send: (ImportRuleAction, Option[BigDecimal]) => Future[Unit])(
        onSaved: () => Unit
    ): Unit =
      checkInputs(form) match {
        case Left(msg) => setError(msg)
        case Right(inputs) =>
          resolveAction(form, inputs.description)
            .flatMap(action => send(action, inputs.amount))
            .onComplete {
              case Success(_) => onSaved(); setError(""); props.onRefresh()
              case Failure(ex) => setError(ex.getMessage)
            }
      }

    def handleCreate(): Unit =
      submit(
        newForm,
        (action, amount) =>
          props.ctx.api.client
            .createImportRule(CreateImportRule.Request(action, newForm.pattern, amount))
            .map(_ => ())
      )(() => setNewForm(cur => RuleForm.empty.copy(kind = cur.kind)))

    def handleDelete(ruleId: UUID): Unit =
      props.ctx.api.client
        .deleteImportRule(ruleId)
        .onComplete {
          case Success(_) => props.onRefresh()
          case Failure(ex) => setError(ex.getMessage)
        }

    def handleUpdate(ruleId: UUID): Unit =
      submit(
        editForm,
        (action, amount) =>
          props.ctx.api.client
            .updateImportRule(ruleId, UpdateImportRule.Request(action, editForm.pattern, amount))
            .map(_ => ())
      ) { () =>
        setEditingId(None); setEditForm(RuleForm.empty)
      }

    def actionSelector(current: ImportRuleActionKind, onChange: ImportRuleActionKind => Unit): ReactElement = {
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
        .onChange(e => onChange(ImportRuleActionKind.fromEntryName(e.target.asInstanceOf[dom.HTMLInputElement].value)))
        .sx(new CSSPropertiesUtils { minWidth = "140px" })(items*)
    }

    def merchantAutocomplete(
        inputValue: String,
        onInputChange: String => Unit,
        onIdChange: Option[UUID] => Unit
    ): ReactElement = {
      val acProps = MuiCompat.freeSoloAutocompleteProps(
        options = localMerchants,
        renderInput = renderMerchantTextField,
        inputValue = inputValue,
        getOptionLabel = AutocompleteLabels.merchantOptionLabel,
        onSelect = value =>
          (value: Any) match {
            case m: GetMerchants.Response.Merchant => onIdChange(Some(m.id)); onInputChange(m.name)
            case _ => ()
          },
        onInput = (text, rsn) => {
          onInputChange(text)
          if (rsn != "reset") onIdChange(None)
        },
        disabled = false,
        fullWidth = false
      )
      (mui.Autocomplete.withProps(acProps).withKey("merchant-ac"): ReactElement)
    }

    def categoryAutocomplete(
        key: String,
        renderInput: AutocompleteRenderInputParams => ReactElement,
        inputValue: String,
        onInputChange: String => Unit,
        onIdChange: Option[UUID] => Unit
    ): ReactElement = {
      val acProps = MuiCompat.freeSoloAutocompleteProps(
        options = localCategories,
        renderInput = renderInput,
        inputValue = inputValue,
        getOptionLabel = AutocompleteLabels.categoryOptionLabel,
        onSelect = value =>
          (value: Any) match {
            case c: GetCategories.Response.Category => onIdChange(Some(c.id)); onInputChange(c.name)
            case _ => ()
          },
        onInput = (text, rsn) => {
          onInputChange(text)
          if (rsn != "reset") onIdChange(None)
        },
        disabled = false,
        fullWidth = false
      )
      (mui.Autocomplete.withProps(acProps).withKey(key): ReactElement)
    }

    /** The action-specific and optional fields shared by the create and edit forms. */
    def ruleFields(current: RuleForm, update: (RuleForm => RuleForm) => Unit): Seq[ReactElement] = {
      val targetFields: Seq[ReactElement] = current.kind match {
        case ImportRuleActionKind.SetMerchant =>
          Seq(
            merchantAutocomplete(
              current.merchantInput,
              v => update(_.copy(merchantInput = v)),
              id => update(_.copy(merchantId = id))
            ),
            categoryAutocomplete(
              key = "override-category-ac",
              renderInput = renderOverrideCategoryTextField,
              inputValue = current.overrideCategoryInput,
              onInputChange = v => update(_.copy(overrideCategoryInput = v)),
              onIdChange = id => update(_.copy(overrideCategoryId = id))
            )
          )
        case ImportRuleActionKind.SetCategory =>
          Seq(
            categoryAutocomplete(
              key = "category-ac",
              renderInput = renderCategoryTextField,
              inputValue = current.categoryInput,
              onInputChange = v => update(_.copy(categoryInput = v)),
              onIdChange = id => update(_.copy(categoryId = id))
            )
          )
        case ImportRuleActionKind.Ignore => Seq.empty
      }
      val amountField: ReactElement =
        mui.TextField
          .outlined()
          .label("Amount (signed, e.g. -12.50)")
          .value(current.amount)
          .size("small")
          .onChange(e => { val v = e.target.asInstanceOf[dom.HTMLInputElement].value; update(_.copy(amount = v)) })
          .withKey("amount")
      val descriptionField: Seq[ReactElement] =
        if (current.kind == ImportRuleActionKind.Ignore) Seq.empty
        else
          Seq(
            mui.TextField
              .outlined()
              .label("Description (optional)")
              .value(current.description)
              .size("small")
              .onChange(e => {
                val v = e.target.asInstanceOf[dom.HTMLInputElement].value; update(_.copy(description = v))
              })
              .withKey("description")
          )
      targetFields ++ (amountField +: descriptionField)
    }

    val createForm = form(onSubmit := { e =>
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
        actionSelector(newForm.kind, k => setNewForm(_.copy(kind = k))),
        mui.TextField
          .outlined()
          .label("Pattern (substring)")
          .value(newForm.pattern)
          .size("small")
          .onChange(e => {
            val v = e.target.asInstanceOf[dom.HTMLInputElement].value; setNewForm(_.copy(pattern = v))
          }),
        Fragment(ruleFields(newForm, f => setNewForm(f))*),
        mui.Button
          .normal()("Add rule")
          .variant("contained")
          .`type`("submit")
          .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)"; borderRadius = "8px" })
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

    val ruleItems: Seq[ReactElement] = pagedRules.map { r =>
      val isEditing = editingId.contains(r.id)

      val content: ReactElement =
        if (isEditing)
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"
            flexWrap = "wrap"
            alignItems = "center"
            gap = "12px"
            padding = "4px 0"
          })(
            actionSelector(editForm.kind, k => setEditForm(_.copy(kind = k))),
            mui.TextField
              .outlined()
              .autoFocus(true)
              .label("Pattern")
              .value(editForm.pattern)
              .size("small")
              .onChange(e => {
                val v = e.target.asInstanceOf[dom.HTMLInputElement].value; setEditForm(_.copy(pattern = v))
              })
              .onKeyDown(e => if (e.key == "Enter") handleUpdate(r.id)),
            Fragment(ruleFields(editForm, f => setEditForm(f))*),
            mui.Button
              .normal()("Save")
              .size("small")
              .variant("contained")
              .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
              .onClick(_ => handleUpdate(r.id)),
            mui.Button
              .normal()("Cancel")
              .size("small")
              .onClick(_ => setEditingId(None))
          )
        else
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
                  setEditingId(Some(r.id))
                  setEditForm(RuleForm.fromRule(r))
                },
              mui.Button
                .normal()("Delete")
                .size("small")
                .sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
                .onClick(_ => handleDelete(r.id))
            )
          )

      mui.Box.sx(cardStyle)(content).withKey(r.id.toString)
    }

    Fragment(
      createForm,
      if (error.nonEmpty)
        mui
          .Typography(error)
          .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px"; marginBottom = "8px" })
      else Fragment(),
      filterBar,
      Fragment(ruleItems*),
      paginationControls
    )
  }
}
