package net.wiringbits.components.pages

import com.olvind.mui.csstype.mod.Property.FlexDirection
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.components.widgets.Loader
import net.wiringbits.api.models.categories.{CreateCategory, GetCategories, UpdateCategory}
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

object CategoriesPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (timesRefreshing, forceRefresh) = GenericHooks.useForceRefresh

    mui.Box.sx(new CSSPropertiesUtils { padding = "24px"; maxWidth = "700px" })(
      AsyncComponent[GetCategories.Response](
        fetch = () => props.ctx.api.client.getCategories,
        render = response => CategoryList(props.ctx, response, () => forceRefresh()),
        progressIndicator = () => Loader(props.ctx),
        watchedObjects = List(timesRefreshing)
      )
    )
  }
}

object CategoryList {
  def apply(ctx: AppContext, response: GetCategories.Response, onRefresh: () => Unit): KeyAddingStage =
    component(Props(ctx, response, onRefresh))

  case class Props(ctx: AppContext, response: GetCategories.Response, onRefresh: () => Unit)

  private val cardStyle = new CSSPropertiesUtils {
    border = "1px solid var(--lc-border)"
    borderRadius = "8px"
    backgroundColor = "var(--lc-paper)"
    marginBottom = "6px"
    padding = "10px 16px"
  }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (newName, setNewName) = Hooks.useState("")
    val (error, setError) = Hooks.useState("")
    val (renamingId, setRenamingId) = Hooks.useState[Option[UUID]](None)
    val (renameValue, setRenameValue) = Hooks.useState("")
    val (searchText, setSearchText) = Hooks.useState("")
    val (page, setPage) = Hooks.useState(0)
    val pageSize = 20
    val filteredCats =
      if (searchText.isEmpty) props.response.data
      else props.response.data.filter(_.name.toLowerCase.contains(searchText.toLowerCase))
    val totalPages = math.ceil(filteredCats.size.toDouble / pageSize).toInt
    val currentPage = math.min(page, math.max(0, totalPages - 1))
    val pagedCats = filteredCats.slice(currentPage * pageSize, (currentPage + 1) * pageSize)
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

    def handleCreate(): Unit =
      props.ctx.api.client
        .createCategory(CreateCategory.Request(newName))
        .onComplete {
          case Success(_) =>
            setNewName("")
            setError("")
            props.onRefresh()
          case Failure(ex) =>
            setError(ex.getMessage)
        }

    def handleDelete(categoryId: UUID): Unit =
      props.ctx.api.client
        .deleteCategory(categoryId)
        .onComplete {
          case Success(_) => props.onRefresh()
          case Failure(ex) => setError(ex.getMessage)
        }

    def handleRename(categoryId: UUID): Unit =
      props.ctx.api.client
        .updateCategory(categoryId, UpdateCategory.Request(renameValue))
        .onComplete {
          case Success(_) =>
            setRenamingId(None)
            setRenameValue("")
            setError("")
            props.onRefresh()
          case Failure(ex) =>
            setError(ex.getMessage)
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
        alignItems = "center"
        gap = "12px"
      })(
        mui.TextField
          .outlined()
          .label("New category name")
          .value(newName)
          .size("small")
          .onChange(e => setNewName(e.target.asInstanceOf[dom.HTMLInputElement].value)),
        mui.Button
          .normal()("Add")
          .variant("contained")
          .`type`("submit")
          .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)"; borderRadius = "8px" })
      )
    )

    val categoryItems: Seq[ReactElement] = pagedCats.map { cat =>
      val isRenaming = renamingId.contains(cat.id)

      val content: ReactElement =
        if (isRenaming)
          Container(
            flexDirection = FlexDirection.row,
            alignItems = Container.Alignment.center,
            child = Fragment(
              mui.TextField
                .outlined()
                .autoFocus(true)
                .size("small")
                .value(renameValue)
                .onChange(e => setRenameValue(e.target.asInstanceOf[dom.HTMLInputElement].value))
                .onKeyDown(e => if (e.key == "Enter") handleRename(cat.id)),
              mui.Button
                .normal()("Save")
                .size("small")
                .variant("contained")
                .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
                .onClick(_ => handleRename(cat.id)),
              mui.Button
                .normal()("Cancel")
                .size("small")
                .onClick(_ => setRenamingId(None))
            )
          )
        else
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"
            alignItems = "center"
            justifyContent = "space-between"
            width = "100%"
          })(
            mui
              .Typography(cat.name)
              .sx(new CSSPropertiesUtils {
                fontSize = "14px"
                fontWeight = "600"
                color = "var(--lc-text)"
                fontFamily = "\"IBM Plex Sans\", sans-serif"
              }),
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "4px" })(
              mui.Button
                .normal()("Rename")
                .size("small")
                .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)" })
                .onClick { _ =>
                  setRenamingId(Some(cat.id))
                  setRenameValue(cat.name)
                },
              mui.Button
                .normal()("Delete")
                .size("small")
                .sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
                .onClick(_ => handleDelete(cat.id))
            )
          )

      mui.Box.sx(cardStyle)(content).withKey(cat.id.toString)
    }

    Fragment(
      createForm,
      if (error.nonEmpty)
        mui
          .Typography(error)
          .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px"; marginBottom = "8px" })
      else Fragment(),
      mui.TextField
        .outlined()
        .label("Search categories")
        .size("small")
        .value(searchText)
        .onChange(e => { setSearchText(e.target.asInstanceOf[dom.HTMLInputElement].value); setPage(0) })
        .sx(new CSSPropertiesUtils { width = "100%"; marginBottom = "12px" }),
      Fragment(categoryItems*),
      paginationWidget
    )
  }
}
