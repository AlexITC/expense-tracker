package net.wiringbits.components.pages.merchants

import com.olvind.mui.csstype.mod.Property.FlexDirection
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.components.widgets.Loader
import net.wiringbits.api.models.categories.{CreateCategory, GetCategories}
import net.wiringbits.common.models.CategoryName
import net.wiringbits.api.models.environmentconfig.GetEnvironmentConfig
import net.wiringbits.api.models.merchants.{CreateImportRule, GetMerchants, ImportRuleAction}
import net.wiringbits.forms.MerchantFormData
import net.wiringbits.ui.components.inputs.{
  MerchantNameInput,
  OptionalIdSelectInput,
  OptionalNotesInput,
  OptionalTaxIdInput
}
import net.wiringbits.ui.forms.{ExpOptionalId, SubmitHooks}
import net.wiringbits.core.SafeHooks
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import net.wiringbits.webapp.utils.slinkyUtils.components.core.widgets.Container
import net.wiringbits.webapp.utils.slinkyUtils.core.GenericHooks
import net.wiringbits.webapp.utils.slinkyUtils.forms.FormField
import net.wiringbits.webapp.common.validators.ValidationResult
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, onSubmit}

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.util.{Failure, Success}

object MerchantsPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (timesRefreshing, forceRefresh) = GenericHooks.useForceRefresh

    mui.Box.sx(new CSSPropertiesUtils { padding = "24px"; maxWidth = "900px" })(
      AsyncComponent[GetEnvironmentConfig.Response](
        fetch = () => props.ctx.api.client.getEnvironmentConfig,
        render = envConfig =>
          AsyncComponent[GetMerchants.Response](
            fetch = () => props.ctx.api.client.getMerchants,
            render = merchants =>
              AsyncComponent[GetCategories.Response](
                fetch = () => props.ctx.api.client.getCategories,
                render = categories =>
                  MerchantList(props.ctx, merchants, categories, () => forceRefresh(), envConfig.maxMerchants),
                progressIndicator = () => Loader(props.ctx),
                watchedObjects = List(timesRefreshing)
              ),
            progressIndicator = () => Loader(props.ctx),
            watchedObjects = List(timesRefreshing)
          ),
        progressIndicator = () => Loader(props.ctx)
      )
    )
  }
}

object MerchantList {
  def apply(
      ctx: AppContext,
      response: GetMerchants.Response,
      categories: GetCategories.Response,
      onRefresh: () => Unit,
      maxMerchants: Int
  ): KeyAddingStage =
    component(Props(ctx, response, categories, onRefresh, maxMerchants))

  case class Props(
      ctx: AppContext,
      response: GetMerchants.Response,
      categories: GetCategories.Response,
      onRefresh: () => Unit,
      maxMerchants: Int
  )

  def usageLabel(count: Int, max: Int): String = s"$count / $max merchants"

  def isAtLimit(count: Int, maxMerchants: Int): Boolean = count >= maxMerchants

  private val cardStyle = new CSSPropertiesUtils {
    border = "1px solid var(--lc-border)"
    borderRadius = "8px"
    backgroundColor = "var(--lc-paper)"
    marginBottom = "6px"
    padding = "12px 16px"
    width = "100%"
  }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (filter, setFilter) = Hooks.useState[MerchantFilter](MerchantFilter.All)
    val (searchText, setSearchText) = Hooks.useState("")
    val (page, setPage) = Hooks.useState(0)
    val (createForm, setCreateForm) =
      Hooks.useState(
        MerchantFormData.initial(prefix = "merchant-create", name = None, notes = "", categoryId = "", taxId = "")
      )
    val createSubmit = SubmitHooks.useSubmit()
    val (error, setError) = Hooks.useState("")
    val (editingId, setEditingId) = Hooks.useState[Option[UUID]](None)
    val (editForm, setEditForm) =
      Hooks.useState(
        MerchantFormData.initial(prefix = "merchant-edit", name = None, notes = "", categoryId = "", taxId = "")
      )
    val editSubmit = SubmitHooks.useSubmit()

    val (createCatOpen, setCreateCatOpen) = Hooks.useState(false)
    val (createCatFor, setCreateCatFor) = Hooks.useState("")
    val (newCatName, setNewCatName) = Hooks.useState("")
    val (catModalError, setCatModalError) = Hooks.useState("")

    val (localCategories, setLocalCategories) = Hooks.useState(props.categories.data)
    val categoriesKey = props.categories.data.map(_.id.toString).mkString(",")
    SafeHooks.useEffect(categoriesKey)(() => { setLocalCategories(props.categories.data); () })

    val (createRuleEnabled, setCreateRuleEnabled) = Hooks.useState(false)
    val (newRulePattern, setNewRulePattern) = Hooks.useState("")

    def categorySelector(
        field: FormField[Option[UUID]],
        onChange: ValidationResult[Option[UUID]] => Unit,
        disabled: Boolean,
        forTarget: String
    ) =
      mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "8px" })(
        mui.Box.sx(new CSSPropertiesUtils { minWidth = "200px" })(
          OptionalIdSelectInput.component(
            OptionalIdSelectInput.Props(
              field = field,
              options = localCategories.map(c => c.id.toString -> c.name),
              onChange = onChange,
              disabled = disabled
            )
          )
        ),
        mui.Button
          .normal()("+ Cat")
          .size("small")
          .variant("outlined")
          .onClick { _ =>
            setCreateCatFor(forTarget)
            setNewCatName("")
            setCatModalError("")
            setCreateCatOpen(true)
          }
      )

    def handleCreateCategory(): Unit =
      if (newCatName.trim.isEmpty) setCatModalError("Name required")
      else
        props.ctx.api.client
          .createCategory(CreateCategory.Request(name = CategoryName.trusted(newCatName.trim), description = None))
          .onComplete {
            case Success(resp) =>
              setLocalCategories(cur =>
                cur :+ GetCategories.Response.Category(resp.id, resp.name, java.time.Instant.now(), None)
              )
              val selected = ExpOptionalId.validate(resp.id.toString)
              if (createCatFor == "create")
                setCreateForm(cur => cur.copy(categoryId = cur.categoryId.updated(selected)))
              else setEditForm(cur => cur.copy(categoryId = cur.categoryId.updated(selected)))
              setCreateCatOpen(false)
              setNewCatName("")
              setCatModalError("")
            case Failure(ex) => setCatModalError(ex.getMessage)
          }

    def handleCreate(): Unit =
      createForm.submitRequest.foreach { request =>
        createSubmit.begin()
        props.ctx.api.client
          .createMerchant(request)
          .onComplete {
            case Success(resp) =>
              createSubmit.succeed()
              setCreateForm(
                MerchantFormData
                  .initial(prefix = "merchant-create", name = None, notes = "", categoryId = "", taxId = "")
              )
              setCreateRuleEnabled(false); setNewRulePattern(""); setError("")
              if (createRuleEnabled && newRulePattern.trim.nonEmpty)
                props.ctx.api.client
                  .createImportRule(
                    CreateImportRule.Request(
                      ImportRuleAction.SetMerchant(resp.id, categoryId = None, description = None),
                      newRulePattern.trim,
                      amount = None
                    )
                  )
                  .onComplete {
                    case Success(_) => props.onRefresh()
                    case Failure(ex) => setError(s"Rule: ${ex.getMessage}"); props.onRefresh()
                  }
              else
                props.onRefresh()
            case Failure(ex) =>
              createSubmit.fail(ex.getMessage)
          }
      }

    def handleDelete(merchantId: UUID): Unit =
      props.ctx.api.client
        .deleteMerchant(merchantId)
        .onComplete {
          case Success(_) => props.onRefresh()
          case Failure(ex) => setError(ex.getMessage)
        }

    def handleUpdate(merchantId: UUID): Unit =
      editForm.updateRequest.foreach { request =>
        editSubmit.begin()
        props.ctx.api.client
          .updateMerchant(merchantId, request)
          .onComplete {
            case Success(_) =>
              editSubmit.succeed()
              setEditingId(None)
              props.onRefresh()
            case Failure(ex) =>
              editSubmit.fail(ex.getMessage)
          }
      }

    val rulePatternField: ReactElement =
      if (createRuleEnabled)
        mui.TextField
          .outlined()
          .label("Rule pattern (substring)")
          .value(newRulePattern)
          .size("small")
          .onChange(e => setNewRulePattern(e.target.asInstanceOf[dom.HTMLInputElement].value))
      else Fragment()

    val merchantCount = props.response.data.size
    val atLimit = isAtLimit(count = merchantCount, maxMerchants = props.maxMerchants)

    val createMerchantForm = form(onSubmit := { e =>
      e.preventDefault()
      if (!atLimit) handleCreate()
    })(
      mui.Box.sx(new CSSPropertiesUtils {
        border = "1px solid var(--lc-brand)"
        borderRadius = "8px"
        padding = "16px"
        backgroundColor = "var(--lc-paper)"
        marginBottom = "16px"
        display = "flex"
        flexDirection = "column"
        gap = "12px"
      })(
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexWrap = "wrap"; alignItems = "center"; gap = "12px" })(
          MerchantNameInput.component(
            MerchantNameInput.Props(
              field = createForm.name,
              disabled = createSubmit.isSubmitting,
              onChange = value => setCreateForm(cur => cur.copy(name = cur.name.updated(value)))
            )
          ),
          OptionalNotesInput.component(
            OptionalNotesInput.Props(
              field = createForm.notes,
              disabled = createSubmit.isSubmitting,
              onChange = v => setCreateForm(cur => cur.copy(notes = cur.notes.updated(v)))
            )
          ),
          OptionalTaxIdInput.component(
            OptionalTaxIdInput.Props(
              field = createForm.taxId,
              disabled = createSubmit.isSubmitting,
              onChange = v => setCreateForm(cur => cur.copy(taxId = cur.taxId.updated(v)))
            )
          ),
          categorySelector(
            field = createForm.categoryId,
            onChange = v => setCreateForm(cur => cur.copy(categoryId = cur.categoryId.updated(v))),
            disabled = createSubmit.isSubmitting,
            forTarget = "create"
          ),
          mui.Button
            .normal()("Add")
            .variant("contained")
            .`type`("submit")
            .disabled(atLimit || createSubmit.buttonDisabled(createForm.isValid))
            .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)"; borderRadius = "8px" })
        ),
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "12px" })(
          mui.Button
            .normal()(if (createRuleEnabled) "- Rule" else "+ Rule")
            .size("small")
            .variant("outlined")
            .onClick { _ =>
              if (!createRuleEnabled) setNewRulePattern(createForm.name.value.map(_.input).getOrElse(""))
              setCreateRuleEnabled(!createRuleEnabled)
            },
          rulePatternField
        ),
        createSubmit.error match {
          case Some(message) =>
            mui
              .Typography(message)
              .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px" })
          case None => Fragment()
        },
        mui
          .Typography(usageLabel(count = merchantCount, max = props.maxMerchants))
          .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" })
      )
    )

    val visibleMerchants = filter match {
      case MerchantFilter.All => props.response.data
      case MerchantFilter.Confirmed => props.response.data.filter(_.reviewed)
      case MerchantFilter.Pending => props.response.data.filterNot(_.reviewed)
    }
    val filteredMerchants =
      if (searchText.isEmpty) visibleMerchants
      else visibleMerchants.filter(_.name.toLowerCase.contains(searchText.toLowerCase))
    val pageSize = 15
    val totalPages = math.ceil(filteredMerchants.size.toDouble / pageSize).toInt
    val currentPage = math.min(page, math.max(0, totalPages - 1))
    val pagedMerchants = filteredMerchants.slice(currentPage * pageSize, (currentPage + 1) * pageSize)
    def paginationWidget: ReactElement =
      if (totalPages <= 1) Fragment()
      else {
        val handler: js.Function2[js.Any, Double, Unit] = (_, p) => setPage(p.toInt - 1)
        val pProps = js.Dynamic
          .literal(count = totalPages, page = currentPage + 1, onChange = handler, size = "small")
          .asInstanceOf[com.olvind.mui.muiMaterial.paginationPaginationMod.PaginationProps]
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; justifyContent = "center"; padding = "12px 0" })(
          mui.Pagination.withProps(pProps)
        )
      }

    val merchantItems: Seq[ReactElement] = pagedMerchants.map { m =>
      val isEditing = editingId.contains(m.id)

      val content: ReactElement =
        if (isEditing)
          form(onSubmit := { e =>
            e.preventDefault()
            handleUpdate(m.id)
          })(
            mui.Box.sx(new CSSPropertiesUtils {
              display = "flex"
              flexWrap = "wrap"
              alignItems = "center"
              gap = "12px"
              padding = "4px 0"
            })(
              MerchantNameInput.component(
                MerchantNameInput.Props(
                  field = editForm.name,
                  disabled = editSubmit.isSubmitting,
                  onChange = value => setEditForm(cur => cur.copy(name = cur.name.updated(value)))
                )
              ),
              OptionalNotesInput.component(
                OptionalNotesInput.Props(
                  field = editForm.notes,
                  disabled = editSubmit.isSubmitting,
                  onChange = v => setEditForm(cur => cur.copy(notes = cur.notes.updated(v)))
                )
              ),
              OptionalTaxIdInput.component(
                OptionalTaxIdInput.Props(
                  field = editForm.taxId,
                  disabled = editSubmit.isSubmitting,
                  onChange = v => setEditForm(cur => cur.copy(taxId = cur.taxId.updated(v)))
                )
              ),
              categorySelector(
                field = editForm.categoryId,
                onChange = v => setEditForm(cur => cur.copy(categoryId = cur.categoryId.updated(v))),
                disabled = editSubmit.isSubmitting,
                forTarget = "edit"
              ),
              mui.Button
                .normal()("Save")
                .size("small")
                .variant("contained")
                .`type`("submit")
                .disabled(editSubmit.buttonDisabled(editForm.isValid))
                .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" }),
              mui.Button
                .normal()("Cancel")
                .size("small")
                .onClick(_ => setEditingId(None)),
              editSubmit.error match {
                case Some(message) =>
                  mui
                    .Typography(message)
                    .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px" })
                case None => Fragment()
              }
            )
          )
        else {
          val categoryName = localCategories.find(c => m.categoryId.contains(c.id)).map(_.name)
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"
            alignItems = "center"
            justifyContent = "space-between"
            width = "100%"
          })(
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "2px" })(
              mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "8px" })(
                mui
                  .Typography(m.name)
                  .sx(new CSSPropertiesUtils {
                    fontSize = "14px"
                    fontWeight = "600"
                    color = "var(--lc-text)"
                    fontFamily = "\"IBM Plex Sans\", sans-serif"
                  }),
                if (!m.reviewed)
                  mui
                    .Typography("pending")
                    .sx(new CSSPropertiesUtils {
                      fontSize = "11px"
                      color = "var(--lc-text-2)"
                      border = "1px solid var(--lc-border)"
                      borderRadius = "4px"
                      padding = "1px 6px"
                    })
                else Fragment()
              ),
              if (m.notes.isDefined || m.taxId.isDefined || categoryName.isDefined)
                mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "8px"; flexWrap = "wrap" })(
                  m.notes match {
                    case Some(n) =>
                      (mui
                        .Typography(n)
                        .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" })
                        .build: ReactElement)
                    case None => Fragment()
                  },
                  m.taxId match {
                    case Some(t) =>
                      (mui
                        .Typography(s"Tax: $t")
                        .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" })
                        .build: ReactElement)
                    case None => Fragment()
                  },
                  categoryName match {
                    case Some(n) =>
                      (mui.Box.sx(new CSSPropertiesUtils {
                        backgroundColor = "var(--lc-tint)"
                        borderRadius = "4px"
                        padding = "1px 8px"
                      })(
                        mui
                          .Typography(n)
                          .sx(new CSSPropertiesUtils {
                            fontSize = "11px"; fontWeight = "600"; color = "var(--lc-brand-dark)"
                          })
                      ): ReactElement)
                    case None => Fragment()
                  }
                )
              else Fragment()
            ),
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "4px" })(
              mui.Button
                .normal()("Edit")
                .size("small")
                .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)" })
                .onClick { _ =>
                  setEditingId(Some(m.id))
                  setEditForm(_ =>
                    MerchantFormData.initial(
                      prefix = "merchant-edit",
                      name = Some(m.name),
                      notes = m.notes.getOrElse(""),
                      categoryId = m.categoryId.map(_.toString).getOrElse(""),
                      taxId = m.taxId.getOrElse("")
                    )
                  )
                  editSubmit.reset()
                },
              mui.Button
                .normal()("Delete")
                .size("small")
                .sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
                .onClick(_ => handleDelete(m.id))
            )
          )
        }

      mui.Box.sx(cardStyle)(content).withKey(m.id.toString)
    }

    val categoryDialog: ReactElement =
      if (!createCatOpen) Fragment()
      else
        mui.Box.sx(new CSSPropertiesUtils {
          border = "1px solid var(--lc-border)"
          borderRadius = "8px"
          padding = "16px"
          backgroundColor = "var(--lc-bg)"
          marginBottom = "12px"
        })(
          mui
            .Typography("New category")
            .sx(new CSSPropertiesUtils { fontWeight = "700"; fontSize = "14px"; marginBottom = "12px" }),
          form(onSubmit := { e =>
            e.preventDefault()
            handleCreateCategory()
          })(
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "12px"; alignItems = "center" })(
              mui.TextField
                .outlined()
                .autoFocus(true)
                .label("Category name")
                .value(newCatName)
                .size("small")
                .onChange(e => setNewCatName(e.target.asInstanceOf[dom.HTMLInputElement].value)),
              mui.Button
                .normal()("Create")
                .variant("contained")
                .size("small")
                .`type`("submit")
                .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" }),
              mui.Button
                .normal()("Cancel")
                .size("small")
                .onClick(_ => setCreateCatOpen(false))
            )
          ),
          if (catModalError.nonEmpty)
            mui
              .Typography(catModalError)
              .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px"; marginTop = "8px" })
          else Fragment()
        )

    def filterButton(label: String, f: MerchantFilter): ReactElement =
      mui.Button
        .normal()(label)
        .variant(if (filter == f) "contained" else "outlined")
        .size("small")
        .sx(if (filter == f) new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" }
        else new CSSPropertiesUtils {})
        .onClick(_ => setFilter(f))

    Fragment(
      createMerchantForm,
      if (atLimit)
        mui
          .Typography(
            s"Maximum number of merchants (${props.maxMerchants}) reached. Delete a merchant to add a new one."
          )
          .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px"; marginBottom = "8px" })
      else Fragment(),
      if (error.nonEmpty)
        mui
          .Typography(error)
          .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px"; marginBottom = "8px" })
      else Fragment(),
      categoryDialog,
      mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "8px"; marginBottom = "12px" })(
        filterButton("All", MerchantFilter.All),
        filterButton("Confirmed", MerchantFilter.Confirmed),
        filterButton("Pending", MerchantFilter.Pending)
      ),
      mui.TextField
        .outlined()
        .label(s"Search merchants (${filteredMerchants.size})")
        .size("small")
        .value(searchText)
        .onChange(e => { setSearchText(e.target.asInstanceOf[dom.HTMLInputElement].value); setPage(0) })
        .sx(new CSSPropertiesUtils { width = "100%"; marginBottom = "12px" }),
      Fragment(merchantItems*),
      paginationWidget
    )
  }
}
