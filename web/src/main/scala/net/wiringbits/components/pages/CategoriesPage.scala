package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.components.widgets.Loader
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.common.models.{CategoryDescription, CategoryName}
import net.wiringbits.forms.CategoryFormData
import net.wiringbits.models.User
import net.wiringbits.ui.components.inputs.{CategoryDescriptionInput, CategoryNameInput}
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.{AsyncComponent, ErrorLabel}
import net.wiringbits.webapp.utils.slinkyUtils.components.core.widgets.Container
import net.wiringbits.webapp.utils.slinkyUtils.core.GenericHooks
import net.wiringbits.webapp.utils.slinkyUtils.forms.StatefulFormData
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage, SyntheticEvent}
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

  private val NameLabel = "Category name"
  private val DescriptionLabel = "Description (optional)"

  private def emptyForm: CategoryFormData =
    CategoryFormData.initial(
      nameLabel = NameLabel,
      descriptionLabel = DescriptionLabel,
      name = None,
      description = None
    )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (createFormData, setCreateFormData) = Hooks.useState(StatefulFormData(emptyForm))
    val (editingId, setEditingId) = Hooks.useState[Option[UUID]](None)
    val (editFormData, setEditFormData) = Hooks.useState(StatefulFormData(emptyForm))
    val (error, setError) = Hooks.useState("")
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

    def onCreateDataChanged(f: CategoryFormData => CategoryFormData): Unit = {
      setCreateFormData { current =>
        current.filling.copy(data = f(current.data))
      }
    }

    def onEditDataChanged(f: CategoryFormData => CategoryFormData): Unit = {
      setEditFormData { current =>
        current.filling.copy(data = f(current.data))
      }
    }

    def handleCreate(e: SyntheticEvent[?, dom.Event]): Unit = {
      e.preventDefault()

      if (createFormData.isSubmitButtonEnabled) {
        setCreateFormData(_.submit)
        for {
          request <- createFormData.data.submitRequest
            .orElse {
              setCreateFormData(_.submissionFailed("Complete the data"))
              None
            }
        } yield props.ctx.api.client
          .createCategory(request)
          .onComplete {
            case Success(_) =>
              setCreateFormData(_ => StatefulFormData(emptyForm))
              props.onRefresh()

            case Failure(ex) =>
              setCreateFormData(_.submissionFailed(ex.getMessage))
          }
      } else {
        println("Submit fired when it is not available")
      }
    }

    def handleEdit(categoryId: UUID)(e: SyntheticEvent[?, dom.Event]): Unit = {
      e.preventDefault()

      if (editFormData.isSubmitButtonEnabled) {
        setEditFormData(_.submit)
        for {
          request <- editFormData.data.updateRequest
            .orElse {
              setEditFormData(_.submissionFailed("Complete the data"))
              None
            }
        } yield props.ctx.api.client
          .updateCategory(categoryId, request)
          .onComplete {
            case Success(_) =>
              setEditFormData(_.submitted)
              setEditingId(None)
              props.onRefresh()

            case Failure(ex) =>
              setEditFormData(_.submissionFailed(ex.getMessage))
          }
      } else {
        println("Submit fired when it is not available")
      }
    }

    def startEditing(cat: GetCategories.Response.Category): Unit = {
      setEditFormData(_ =>
        StatefulFormData(
          CategoryFormData.initial(
            nameLabel = NameLabel,
            descriptionLabel = DescriptionLabel,
            name = Some(CategoryName.trusted(cat.name)),
            description = cat.description.map(CategoryDescription.trusted)
          )
        )
      )
      setEditingId(Some(cat.id))
    }

    def handleDelete(categoryId: UUID): Unit =
      props.ctx.api.client
        .deleteCategory(categoryId)
        .onComplete {
          case Success(_) =>
            setError("")
            props.onRefresh()
          case Failure(ex) => setError(ex.getMessage)
        }

    def validationError(text: Option[String]): ReactElement =
      text
        .map(t => Container(margin = Container.EdgeInsets.top(8), child = ErrorLabel(t)): ReactElement)
        .getOrElse(Fragment())

    val createForm = form(onSubmit := (handleCreate(_)))(
      mui.Box.sx(new CSSPropertiesUtils {
        border = "1px solid var(--lc-brand)"
        borderRadius = "8px"
        padding = "16px"
        backgroundColor = "var(--lc-paper)"
        marginBottom = "16px"
      })(
        CategoryNameInput.component(
          CategoryNameInput.Props(
            createFormData.data.name,
            disabled = createFormData.isInputDisabled,
            onChange = value => onCreateDataChanged(x => x.copy(name = x.name.updated(value)))
          )
        ),
        CategoryDescriptionInput.component(
          CategoryDescriptionInput.Props(
            createFormData.data.description,
            disabled = createFormData.isInputDisabled,
            onChange = value => onCreateDataChanged(x => x.copy(description = x.description.updated(value)))
          )
        ),
        validationError(createFormData.firstValidationError),
        mui.Button
          .normal()("Add")
          .variant("contained")
          .`type`("submit")
          .disabled(createFormData.isSubmitButtonDisabled)
          .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)"; borderRadius = "8px"; marginTop = "8px" })
      )
    )

    def editForm(cat: GetCategories.Response.Category): ReactElement =
      form(onSubmit := (handleEdit(cat.id)(_)))(
        CategoryNameInput.component(
          CategoryNameInput.Props(
            editFormData.data.name,
            disabled = editFormData.isInputDisabled,
            onChange = value => onEditDataChanged(x => x.copy(name = x.name.updated(value)))
          )
        ),
        CategoryDescriptionInput.component(
          CategoryDescriptionInput.Props(
            editFormData.data.description,
            disabled = editFormData.isInputDisabled,
            onChange = value => onEditDataChanged(x => x.copy(description = x.description.updated(value)))
          )
        ),
        validationError(editFormData.firstValidationError),
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "4px"; marginTop = "8px" })(
          mui.Button
            .normal()("Save")
            .size("small")
            .variant("contained")
            .`type`("submit")
            .disabled(editFormData.isSubmitButtonDisabled)
            .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" }),
          mui.Button
            .normal()("Cancel")
            .size("small")
            .onClick(_ => setEditingId(None))
        )
      )

    val categoryItems: Seq[ReactElement] = pagedCats.map { cat =>
      val content: ReactElement =
        if (editingId.contains(cat.id)) editForm(cat)
        else
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"
            alignItems = "center"
            justifyContent = "space-between"
            width = "100%"
          })(
            mui.Box(
              mui
                .Typography(cat.name)
                .sx(new CSSPropertiesUtils {
                  fontSize = "14px"
                  fontWeight = "600"
                  color = "var(--lc-text)"
                  fontFamily = "\"IBM Plex Sans\", sans-serif"
                }),
              cat.description
                .map(description =>
                  mui
                    .Typography(description)
                    .sx(new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text-2)" }): ReactElement
                )
                .getOrElse(Fragment())
            ),
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "4px" })(
              mui.Button
                .normal()("Edit")
                .size("small")
                .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)" })
                .onClick(_ => startEditing(cat)),
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
