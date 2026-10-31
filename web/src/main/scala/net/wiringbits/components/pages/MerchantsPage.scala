package net.wiringbits.components.pages

import com.olvind.mui.csstype.mod.Property.FlexDirection
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.components.widgets.Loader
import net.wiringbits.api.models.categories.{CreateCategory, GetCategories}
import net.wiringbits.api.models.environmentconfig.GetEnvironmentConfig
import net.wiringbits.api.models.merchants.{
  CreateImportRule,
  CreateMerchant,
  GetMerchants,
  ImportRuleAction,
  UpdateMerchant
}
import net.wiringbits.core.SafeHooks
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import net.wiringbits.webapp.utils.slinkyUtils.components.core.widgets.Container
import net.wiringbits.webapp.utils.slinkyUtils.core.GenericHooks
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
    val (newName, setNewName) = Hooks.useState("")
    val (newNotes, setNewNotes) = Hooks.useState("")
    val (newCategoryId, setNewCategoryId) = Hooks.useState("")
    val (newTaxId, setNewTaxId) = Hooks.useState("")
    val (error, setError) = Hooks.useState("")
    val (editingId, setEditingId) = Hooks.useState[Option[UUID]](None)
    val (editName, setEditName) = Hooks.useState("")
    val (editNotes, setEditNotes) = Hooks.useState("")
    val (editCategoryId, setEditCategoryId) = Hooks.useState("")
    val (editTaxId, setEditTaxId) = Hooks.useState("")

    val (createCatOpen, setCreateCatOpen) = Hooks.useState(false)
    val (createCatFor, setCreateCatFor) = Hooks.useState("")
    val (newCatName, setNewCatName) = Hooks.useState("")
    val (catModalError, setCatModalError) = Hooks.useState("")

    val (localCategories, setLocalCategories) = Hooks.useState(props.categories.data)
    val categoriesKey = props.categories.data.map(_.id.toString).mkString(",")
    SafeHooks.useEffect(categoriesKey)(() => { setLocalCategories(props.categories.data); () })

    val (createRuleEnabled, setCreateRuleEnabled) = Hooks.useState(false)
    val (newRulePattern, setNewRulePattern) = Hooks.useState("")

    def parseCategoryId(s: String): Option[UUID] =
      if (s.isEmpty) None else Some(UUID.fromString(s))

    def categoryItems = {
      val none = mui.MenuItem.normal()("(none)").value("").withKey("none").build
      none +: localCategories.map(c => mui.MenuItem.normal()(c.name).value(c.id.toString).withKey(c.id.toString).build)
    }

    def categorySelector(value: String, onChange: String => Unit, label: String, forTarget: String) =
      mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "8px" })(
        mui.TextField
          .outlined()
          .select(true)
          .label(label)
          .value(value)
          .size("small")
          .onChange(e => onChange(e.target.asInstanceOf[dom.HTMLInputElement].value))
          .sx(new CSSPropertiesUtils { minWidth = "200px" })(categoryItems*),
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
          .createCategory(CreateCategory.Request(newCatName.trim))
          .onComplete {
            case Success(resp) =>
              setLocalCategories(cur =>
                cur :+ GetCategories.Response.Category(resp.id, resp.name, java.time.Instant.now())
              )
              if (createCatFor == "create") setNewCategoryId(resp.id.toString)
              else setEditCategoryId(resp.id.toString)
              setCreateCatOpen(false)
              setNewCatName("")
              setCatModalError("")
            case Failure(ex) => setCatModalError(ex.getMessage)
          }

    def handleCreate(): Unit =
      props.ctx.api.client
        .createMerchant(
          CreateMerchant.Request(
            newName,
            Option(newNotes.trim).filter(_.nonEmpty),
            parseCategoryId(newCategoryId),
            Option(newTaxId.trim).filter(_.nonEmpty)
          )
        )
        .onComplete {
          case Success(resp) =>
            setNewName(""); setNewNotes(""); setNewCategoryId(""); setNewTaxId("")
            setCreateRuleEnabled(false); setNewRulePattern(""); setError("")
            if (createRuleEnabled && newRulePattern.trim.nonEmpty)
              props.ctx.api.client
                .createImportRule(
                  CreateImportRule.Request(ImportRuleAction.SetMerchant(resp.id), newRulePattern.trim)
                )
                .onComplete {
                  case Success(_) => props.onRefresh()
                  case Failure(ex) => setError(s"Rule: ${ex.getMessage}"); props.onRefresh()
                }
            else
              props.onRefresh()
          case Failure(ex) =>
            setError(ex.getMessage)
        }

    def handleDelete(merchantId: UUID): Unit =
      props.ctx.api.client
        .deleteMerchant(merchantId)
        .onComplete {
          case Success(_) => props.onRefresh()
          case Failure(ex) => setError(ex.getMessage)
        }

    def handleUpdate(merchantId: UUID): Unit =
      props.ctx.api.client
        .updateMerchant(
          merchantId,
          UpdateMerchant.Request(
            editName,
            Option(editNotes.trim).filter(_.nonEmpty),
            parseCategoryId(editCategoryId),
            Option(editTaxId.trim).filter(_.nonEmpty)
          )
        )
        .onComplete {
          case Success(_) =>
            setEditingId(None); setEditName(""); setEditNotes(""); setEditCategoryId(""); setEditTaxId(""); setError("")
            props.onRefresh()
          case Failure(ex) =>
            setError(ex.getMessage)
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
    val atLimit = isAtLimit(merchantCount, props.maxMerchants)

    val createForm = form(onSubmit := { e =>
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
          mui.TextField
            .outlined()
            .label("Merchant name")
            .value(newName)
            .size("small")
            .onChange(e => setNewName(e.target.asInstanceOf[dom.HTMLInputElement].value)),
          mui.TextField
            .outlined()
            .label("Notes (optional)")
            .value(newNotes)
            .size("small")
            .onChange(e => setNewNotes(e.target.asInstanceOf[dom.HTMLInputElement].value)),
          mui.TextField
            .outlined()
            .label("Tax ID (optional)")
            .value(newTaxId)
            .size("small")
            .onChange(e => setNewTaxId(e.target.asInstanceOf[dom.HTMLInputElement].value)),
          categorySelector(newCategoryId, setNewCategoryId, "Default category", "create"),
          mui.Button
            .normal()("Add")
            .variant("contained")
            .`type`("submit")
            .disabled(atLimit)
            .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)"; borderRadius = "8px" })
        ),
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "12px" })(
          mui.Button
            .normal()(if (createRuleEnabled) "- Rule" else "+ Rule")
            .size("small")
            .variant("outlined")
            .onClick { _ =>
              if (!createRuleEnabled) setNewRulePattern(newName)
              setCreateRuleEnabled(!createRuleEnabled)
            },
          rulePatternField
        ),
        mui
          .Typography(usageLabel(merchantCount, props.maxMerchants))
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
              mui.TextField
                .outlined()
                .autoFocus(true)
                .label("Name")
                .value(editName)
                .size("small")
                .onChange(e => setEditName(e.target.asInstanceOf[dom.HTMLInputElement].value)),
              mui.TextField
                .outlined()
                .label("Notes (optional)")
                .value(editNotes)
                .size("small")
                .onChange(e => setEditNotes(e.target.asInstanceOf[dom.HTMLInputElement].value)),
              mui.TextField
                .outlined()
                .label("Tax ID (optional)")
                .value(editTaxId)
                .size("small")
                .onChange(e => setEditTaxId(e.target.asInstanceOf[dom.HTMLInputElement].value)),
              categorySelector(editCategoryId, setEditCategoryId, "Default category", "edit"),
              mui.Button
                .normal()("Save")
                .size("small")
                .variant("contained")
                .`type`("submit")
                .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" }),
              mui.Button
                .normal()("Cancel")
                .size("small")
                .onClick(_ => setEditingId(None))
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
                  setEditName(m.name)
                  setEditNotes(m.notes.getOrElse(""))
                  setEditCategoryId(m.categoryId.map(_.toString).getOrElse(""))
                  setEditTaxId(m.taxId.getOrElse(""))
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
      createForm,
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
