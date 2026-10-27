package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.autocompleteAutocompleteMod.{AutocompleteProps, AutocompleteRenderInputParams}
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.components.widgets.Loader
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.{
  CreateImportRule,
  CreateMerchant,
  GetImportRules,
  GetMerchants,
  ImportRuleAction,
  ImportRuleActionKind,
  UpdateImportRule
}
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
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
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
        r.categoryName.exists(_.toLowerCase.contains(q))
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

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (newAction, setNewAction) = Hooks.useState(ImportRuleActionKind.SetMerchant: ImportRuleActionKind)
    val (newPattern, setNewPattern) = Hooks.useState("")
    val (newMerchantInput, setNewMerchantInput) = Hooks.useState("")
    val (newMerchantIdOpt, setNewMerchantIdOpt) = Hooks.useState[Option[UUID]](None)
    val (newCategoryInput, setNewCategoryInput) = Hooks.useState("")
    val (newCategoryIdOpt, setNewCategoryIdOpt) = Hooks.useState[Option[UUID]](None)
    val (error, setError) = Hooks.useState("")
    val (editingId, setEditingId) = Hooks.useState[Option[UUID]](None)
    val (editAction, setEditAction) = Hooks.useState(ImportRuleActionKind.SetMerchant: ImportRuleActionKind)
    val (editPattern, setEditPattern) = Hooks.useState("")
    val (editMerchantInput, setEditMerchantInput) = Hooks.useState("")
    val (editMerchantIdOpt, setEditMerchantIdOpt) = Hooks.useState[Option[UUID]](None)
    val (editCategoryInput, setEditCategoryInput) = Hooks.useState("")
    val (editCategoryIdOpt, setEditCategoryIdOpt) = Hooks.useState[Option[UUID]](None)

    val (localMerchants, setLocalMerchants) = Hooks.useState(props.merchants.data)
    val merchantsKey = props.merchants.data.map(_.id.toString).mkString(",")
    Hooks.useEffect(
      () => { setLocalMerchants(props.merchants.data); () },
      js.Array(merchantsKey)
    )

    val (localCategories, setLocalCategories) = Hooks.useState(props.categories.data)
    val categoriesKey = props.categories.data.map(_.id.toString).mkString(",")
    Hooks.useEffect(
      () => { setLocalCategories(props.categories.data); () },
      js.Array(categoriesKey)
    )

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
                .createMerchant(CreateMerchant.Request(name, None, None, None))
                .map { resp =>
                  setLocalMerchants(cur =>
                    cur :+ GetMerchants.Response
                      .Merchant(resp.id, resp.name, None, None, None, reviewed = false, java.time.Instant.now())
                  )
                  resp.id
                }
          }
      }

    def resolveOrCreateCategory(
        categoryInput: String,
        categoryIdOpt: Option[UUID]
    ): Future[UUID] =
      DescriptionRow
        .resolveOrCreateCategory(props.ctx.api.client, localCategories, categoryIdOpt, categoryInput)
        .map { case (id, newCategoryOpt) =>
          newCategoryOpt.foreach(c => setLocalCategories(cur => cur :+ c))
          id
        }

    def handleCreate(): Unit =
      newAction match {
        case ImportRuleActionKind.Ignore =>
          props.ctx.api.client
            .createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, newPattern))
            .onComplete {
              case Success(_) =>
                setNewPattern(""); setNewMerchantInput(""); setNewMerchantIdOpt(None); setError(""); props.onRefresh()
              case Failure(ex) => setError(ex.getMessage)
            }

        case ImportRuleActionKind.SetMerchant =>
          val name = newMerchantInput.trim
          if (name.isEmpty) setError("Enter a merchant name")
          else
            resolveOrCreateMerchant(name, newMerchantIdOpt)
              .flatMap { mid =>
                props.ctx.api.client
                  .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant(mid), newPattern))
              }
              .onComplete {
                case Success(_) =>
                  setNewPattern(""); setNewMerchantInput(""); setNewMerchantIdOpt(None); setError(""); props.onRefresh()
                case Failure(ex) => setError(ex.getMessage)
              }

        case ImportRuleActionKind.SetCategory =>
          val name = newCategoryInput.trim
          if (name.isEmpty) setError("Enter a category name")
          else
            resolveOrCreateCategory(name, newCategoryIdOpt)
              .flatMap { cid =>
                props.ctx.api.client
                  .createImportRule(CreateImportRule.Request(ImportRuleAction.SetCategory(cid), newPattern))
              }
              .onComplete {
                case Success(_) =>
                  setNewPattern(""); setNewCategoryInput(""); setNewCategoryIdOpt(None); setError(""); props.onRefresh()
                case Failure(ex) => setError(ex.getMessage)
              }
      }

    def handleDelete(ruleId: UUID): Unit =
      props.ctx.api.client
        .deleteImportRule(ruleId)
        .onComplete {
          case Success(_) => props.onRefresh()
          case Failure(ex) => setError(ex.getMessage)
        }

    def handleUpdate(ruleId: UUID): Unit =
      editAction match {
        case ImportRuleActionKind.Ignore =>
          props.ctx.api.client
            .updateImportRule(ruleId, UpdateImportRule.Request(ImportRuleAction.Ignore, editPattern))
            .onComplete {
              case Success(_) =>
                setEditingId(None); setEditPattern(""); setEditMerchantInput(""); setEditMerchantIdOpt(None)
                setError(""); props.onRefresh()
              case Failure(ex) => setError(ex.getMessage)
            }

        case ImportRuleActionKind.SetMerchant =>
          val name = editMerchantInput.trim
          if (name.isEmpty) setError("Enter a merchant name")
          else
            resolveOrCreateMerchant(name, editMerchantIdOpt)
              .flatMap { mid =>
                props.ctx.api.client
                  .updateImportRule(
                    ruleId,
                    UpdateImportRule.Request(ImportRuleAction.SetMerchant(mid), editPattern)
                  )
              }
              .onComplete {
                case Success(_) =>
                  setEditingId(None); setEditPattern(""); setEditMerchantInput(""); setEditMerchantIdOpt(None)
                  setError(""); props.onRefresh()
                case Failure(ex) => setError(ex.getMessage)
              }

        case ImportRuleActionKind.SetCategory =>
          val name = editCategoryInput.trim
          if (name.isEmpty) setError("Enter a category name")
          else
            resolveOrCreateCategory(name, editCategoryIdOpt)
              .flatMap { cid =>
                props.ctx.api.client
                  .updateImportRule(
                    ruleId,
                    UpdateImportRule.Request(ImportRuleAction.SetCategory(cid), editPattern)
                  )
              }
              .onComplete {
                case Success(_) =>
                  setEditingId(None); setEditPattern(""); setEditCategoryInput(""); setEditCategoryIdOpt(None)
                  setError(""); props.onRefresh()
                case Failure(ex) => setError(ex.getMessage)
              }
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
      val acProps = AutocompleteProps[GetMerchants.Response.Merchant, Boolean, Boolean, Boolean, "div"](
        options = localMerchants.toJSArray,
        renderInput = renderMerchantTextField
      )
      val acDyn = acProps.asInstanceOf[js.Dynamic]
      acDyn.freeSolo = true
      acDyn.size = "small"
      acDyn.inputValue = inputValue
      acDyn.getOptionLabel = (opt: js.Any) => DescriptionRow.merchantOptionLabel(opt)
      acDyn.onChange = (_: js.Any, value: js.Any, _: js.Any, _: js.Any) =>
        (value: Any) match {
          case m: GetMerchants.Response.Merchant => onIdChange(Some(m.id)); onInputChange(m.name)
          case _ => ()
        }
      acDyn.onInputChange = (_: js.Any, value: js.Any, reason: js.Any) => {
        val (text, rsn) = MuiCompat.autocompleteInputChange(value, reason)
        onInputChange(text)
        if (rsn != "reset") onIdChange(None)
      }
      (mui.Autocomplete.withProps(acProps).withKey("merchant-ac"): ReactElement)
    }

    def categoryAutocomplete(
        inputValue: String,
        onInputChange: String => Unit,
        onIdChange: Option[UUID] => Unit
    ): ReactElement = {
      val acProps = AutocompleteProps[GetCategories.Response.Category, Boolean, Boolean, Boolean, "div"](
        options = localCategories.toJSArray,
        renderInput = renderCategoryTextField
      )
      val acDyn = acProps.asInstanceOf[js.Dynamic]
      acDyn.freeSolo = true
      acDyn.size = "small"
      acDyn.inputValue = inputValue
      acDyn.getOptionLabel = (opt: js.Any) => DescriptionRow.categoryOptionLabel(opt)
      acDyn.onChange = (_: js.Any, value: js.Any, _: js.Any, _: js.Any) =>
        (value: Any) match {
          case c: GetCategories.Response.Category => onIdChange(Some(c.id)); onInputChange(c.name)
          case _ => ()
        }
      acDyn.onInputChange = (_: js.Any, value: js.Any, reason: js.Any) => {
        val (text, rsn) = MuiCompat.autocompleteInputChange(value, reason)
        onInputChange(text)
        if (rsn != "reset") onIdChange(None)
      }
      (mui.Autocomplete.withProps(acProps).withKey("category-ac"): ReactElement)
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
        actionSelector(newAction, setNewAction),
        mui.TextField
          .outlined()
          .label("Pattern (substring)")
          .value(newPattern)
          .size("small")
          .onChange(e => setNewPattern(e.target.asInstanceOf[dom.HTMLInputElement].value)),
        newAction match {
          case ImportRuleActionKind.SetMerchant =>
            merchantAutocomplete(newMerchantInput, setNewMerchantInput, setNewMerchantIdOpt)
          case ImportRuleActionKind.SetCategory =>
            categoryAutocomplete(newCategoryInput, setNewCategoryInput, setNewCategoryIdOpt)
          case ImportRuleActionKind.Ignore => Fragment()
        },
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
            actionSelector(editAction, setEditAction),
            mui.TextField
              .outlined()
              .autoFocus(true)
              .label("Pattern")
              .value(editPattern)
              .size("small")
              .onChange(e => setEditPattern(e.target.asInstanceOf[dom.HTMLInputElement].value))
              .onKeyDown(e => if (e.key == "Enter") handleUpdate(r.id)),
            editAction match {
              case ImportRuleActionKind.SetMerchant =>
                merchantAutocomplete(editMerchantInput, setEditMerchantInput, setEditMerchantIdOpt)
              case ImportRuleActionKind.SetCategory =>
                categoryAutocomplete(editCategoryInput, setEditCategoryInput, setEditCategoryIdOpt)
              case ImportRuleActionKind.Ignore => Fragment()
            },
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
        else {
          val actionLabel = r.action match {
            case ImportRuleAction.Ignore => "Ignore"
            case ImportRuleAction.SetMerchant(_) => s"→ ${r.merchantName.getOrElse("?")}"
            case ImportRuleAction.SetCategory(_) => s"→ ${r.categoryName.getOrElse("?")}"
          }
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
                .Typography(actionLabel)
                .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" })
            ),
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "4px" })(
              mui.Button
                .normal()("Edit")
                .size("small")
                .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)" })
                .onClick { _ =>
                  setEditingId(Some(r.id))
                  setEditAction(r.action.kind)
                  setEditPattern(r.pattern)
                  setEditMerchantInput(r.merchantName.getOrElse(""))
                  setEditMerchantIdOpt(r.action.merchantIdOpt)
                  setEditCategoryInput(r.categoryName.getOrElse(""))
                  setEditCategoryIdOpt(r.action.categoryIdOpt)
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
