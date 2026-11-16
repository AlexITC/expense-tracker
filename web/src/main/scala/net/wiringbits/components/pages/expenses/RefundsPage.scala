package net.wiringbits.components.pages.expenses

import com.olvind.mui.csstype.mod.Property.FlexDirection
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.components.widgets.Loader
import net.wiringbits.forms.RefundFormData
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.models.refunds.{CreateRefund, GetRefunds, LinkRefundToExpense, UpdateRefund}
import net.wiringbits.models.User
import net.wiringbits.ui.components.inputs.{AmountInput, DateInput, OptionalIdSelectInput, OptionalNotesInput}
import net.wiringbits.ui.forms.{ExpLocalDate, PositiveBigDecimal, SubmitHooks}
import net.wiringbits.webapp.common.validators.ValidationResult
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

object RefundsPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (timesRefreshing, forceRefresh) = GenericHooks.useForceRefresh

    mui.Box.sx(new CSSPropertiesUtils { padding = "24px"; maxWidth = "900px" })(
      AsyncComponent[GetRefunds.Response](
        fetch = () => props.ctx.api.client.getRefunds,
        render = refunds =>
          AsyncComponent[GetMerchants.Response](
            fetch = () => props.ctx.api.client.getMerchants,
            render = merchants =>
              AsyncComponent[GetCategories.Response](
                fetch = () => props.ctx.api.client.getCategories,
                render = categories =>
                  AsyncComponent[GetExpenses.Response](
                    fetch = () => props.ctx.api.client.getExpenses,
                    render =
                      expenses => RefundList(props.ctx, refunds, merchants, categories, expenses, () => forceRefresh()),
                    progressIndicator = () => Loader(props.ctx),
                    watchedObjects = List(timesRefreshing)
                  ),
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

object RefundList {
  def apply(
      ctx: AppContext,
      refunds: GetRefunds.Response,
      merchants: GetMerchants.Response,
      categories: GetCategories.Response,
      expenses: GetExpenses.Response,
      onRefresh: () => Unit
  ): KeyAddingStage =
    component(Props(ctx, refunds, merchants, categories, expenses, onRefresh))

  case class Props(
      ctx: AppContext,
      refunds: GetRefunds.Response,
      merchants: GetMerchants.Response,
      categories: GetCategories.Response,
      expenses: GetExpenses.Response,
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

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (createForm, setCreateForm) = Hooks.useState(RefundFormData.initial)
    val createSubmit = SubmitHooks.useSubmit()
    val (editing, setEditing) = Hooks.useState[Option[(UUID, RefundFormData)]](None)
    val editSubmit = SubmitHooks.useSubmit()

    val (error, setError) = Hooks.useState("")

    val (linkingExpenseForRefund, setLinkingExpenseForRefund) = Hooks.useState[Option[UUID]](None)
    val (expenseFilter, setExpenseFilter) = Hooks.useState("")
    val (searchText, setSearchText) = Hooks.useState("")
    val (page, setPage) = Hooks.useState(0)
    val pageSize = 15
    val filteredRefunds =
      if (searchText.isEmpty) props.refunds.data
      else {
        val q = searchText.toLowerCase
        props.refunds.data.filter { r =>
          r.merchantName.exists(_.toLowerCase.contains(q)) ||
          r.notes.exists(_.toLowerCase.contains(q)) ||
          r.date.toString.contains(q) ||
          r.amount.toString.contains(q)
        }
      }
    val totalPages = math.ceil(filteredRefunds.size.toDouble / pageSize).toInt
    val currentPage = math.min(page, math.max(0, totalPages - 1))
    val pagedRefunds = filteredRefunds.slice(currentPage * pageSize, (currentPage + 1) * pageSize)
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

    def save(
        formData: RefundFormData,
        submit: SubmitHooks.Control,
        send: RefundFormData.Resolved => scala.concurrent.Future[Unit]
    )(onSaved: () => Unit): Unit =
      formData.submitRequest match {
        case None =>
          submit.fail(formData.formValidationErrors.headOption.getOrElse("Please fix the highlighted fields"))
        case Some(resolved) =>
          submit.begin()
          send(resolved).onComplete {
            case Success(_) => onSaved(); props.onRefresh()
            case Failure(ex) => submit.fail(ex.getMessage)
          }
      }

    def handleCreate(): Unit =
      save(
        createForm,
        createSubmit,
        r =>
          props.ctx.api.client
            .createRefund(
              CreateRefund.Request(
                r.amount,
                r.date,
                merchantId = r.merchantId,
                categoryId = r.categoryId,
                expenseId = None,
                r.notes
              )
            )
            .map(_ => ())
      ) { () =>
        createSubmit.reset()
        setCreateForm(RefundFormData.initial)
      }

    def handleDelete(refundId: UUID): Unit =
      props.ctx.api.client
        .deleteRefund(refundId)
        .onComplete {
          case Success(_) => props.onRefresh()
          case Failure(ex) => setError(ex.getMessage)
        }

    def handleUpdate(refundId: UUID, formData: RefundFormData): Unit =
      save(
        formData,
        editSubmit,
        r =>
          props.ctx.api.client
            .updateRefund(
              refundId,
              UpdateRefund.Request(r.amount, r.date, merchantId = r.merchantId, categoryId = r.categoryId, r.notes)
            )
            .map(_ => ())
      ) { () =>
        editSubmit.reset()
        setEditing(None)
      }

    type Update = (RefundFormData => RefundFormData) => Unit

    val updateCreate: Update = f => setCreateForm(cur => f(cur))
    val updateEdit: Update = f => setEditing(cur => cur.map { case (id, fd) => (id, f(fd)) })

    def submitError(submit: SubmitHooks.Control): ReactElement =
      submit.error match {
        case Some(msg) =>
          mui
            .Typography(msg)
            .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px"; width = "100%" })
        case None => Fragment()
      }

    /** Amount, date, merchant, category and notes inputs, shared by the create and the edit form. */
    def formFields(formData: RefundFormData, disabled: Boolean, update: Update): Seq[ReactElement] = Seq(
      mui.Box.sx(new CSSPropertiesUtils { width = "140px" })(
        AmountInput.component(
          AmountInput.Props(
            field = formData.amount,
            disabled = disabled,
            onChange = (value: ValidationResult[PositiveBigDecimal]) =>
              update(cur => cur.copy(amount = cur.amount.updated(value)))
          )
        )
      ),
      mui.Box.sx(new CSSPropertiesUtils { width = "170px" })(
        DateInput.component(
          DateInput.Props(
            field = formData.date,
            disabled = disabled,
            onChange =
              (value: ValidationResult[ExpLocalDate]) => update(cur => cur.copy(date = cur.date.updated(value)))
          )
        )
      ),
      mui.Box.sx(new CSSPropertiesUtils { minWidth = "160px" })(
        OptionalIdSelectInput.component(
          OptionalIdSelectInput.Props(
            field = formData.merchantId,
            options = props.merchants.data.map(m => m.id.toString -> m.name),
            disabled = disabled,
            onChange = v => update(_.withMerchant(v, props.merchants.data))
          )
        )
      ),
      mui.Box.sx(new CSSPropertiesUtils { minWidth = "160px" })(
        OptionalIdSelectInput.component(
          OptionalIdSelectInput.Props(
            field = formData.categoryId,
            options = props.categories.data.map(c => c.id.toString -> c.name),
            disabled = disabled,
            onChange = v => update(cur => cur.copy(categoryId = cur.categoryId.updated(v)))
          )
        )
      ),
      OptionalNotesInput.component(
        OptionalNotesInput.Props(
          field = formData.notes,
          disabled = disabled,
          onChange = v => update(cur => cur.copy(notes = cur.notes.updated(v)))
        )
      )
    )

    def handleLinkToExpense(refundId: UUID, expenseId: UUID): Unit =
      props.ctx.api.client
        .linkRefundToExpense(refundId, LinkRefundToExpense.Request(expenseId))
        .onComplete {
          case Success(_) =>
            setError("")
            setLinkingExpenseForRefund(None)
            props.onRefresh()
          case Failure(ex) => setError(ex.getMessage)
        }

    def handleUnlinkFromExpense(refundId: UUID): Unit =
      props.ctx.api.client
        .unlinkRefundFromExpense(refundId)
        .onComplete {
          case Success(_) => props.onRefresh()
          case Failure(ex) => setError(ex.getMessage)
        }

    def renderExpenseLinkPicker(refundId: UUID): ReactElement = {
      val filtered = props.expenses.data.filter { e =>
        val f = expenseFilter.toLowerCase
        f.isEmpty ||
        e.date.toString.contains(f) ||
        e.amount.toString.contains(f) ||
        e.merchantName.exists(_.toLowerCase.contains(f)) ||
        e.notes.exists(_.toLowerCase.contains(f))
      }
      mui.Box.sx(new CSSPropertiesUtils {
        border = "1px solid var(--lc-border)"
        borderRadius = "8px"
        padding = "12px"
        backgroundColor = "var(--lc-bg)"
        display = "flex"
        flexDirection = "column"
        gap = "8px"
      })(
        mui
          .Typography("Link to existing expense")
          .sx(new CSSPropertiesUtils { fontWeight = "600"; fontSize = "13px"; color = "var(--lc-text)" }),
        mui.TextField
          .outlined()
          .label("Filter")
          .value(expenseFilter)
          .size("small")
          .onChange(e => setExpenseFilter(e.target.asInstanceOf[dom.HTMLInputElement].value)),
        Fragment(
          filtered.take(20).map { e =>
            val label = s"${e.date} | ${e.amount} | ${e.merchantName.getOrElse("-")} | ${e.notes.getOrElse("")}"
            mui.Box
              .sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "8px" })(
                mui
                  .Typography(label)
                  .sx(new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text-2)"; flex = "1" }),
                mui.Button
                  .normal()("Link")
                  .size("small")
                  .variant("contained")
                  .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
                  .onClick(_ => handleLinkToExpense(refundId = refundId, expenseId = e.id))
              )
              .withKey(e.id.toString)
          }*
        ),
        mui.Button
          .normal()("Cancel")
          .size("small")
          .onClick(_ => setLinkingExpenseForRefund(None))
      )
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
        Fragment(formFields(createForm, createSubmit.isSubmitting, updateCreate)*),
        mui.Button
          .normal()("Add")
          .variant("contained")
          .disabled(createSubmit.buttonDisabled(createForm.isValid))
          .`type`("submit")
          .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)"; borderRadius = "8px" }),
        submitError(createSubmit)
      )
    )

    val refundItems: Seq[ReactElement] = pagedRefunds.map { r =>
      val editingForm = editing.collect { case (id, fd) if id == r.id => fd }
      val isLinkingExpense = linkingExpenseForRefund.contains(r.id)

      val content: ReactElement = editingForm match {
        case Some(editForm) =>
          form(onSubmit := { ev =>
            ev.preventDefault()
            handleUpdate(r.id, editForm)
          })(
            mui.Box.sx(new CSSPropertiesUtils {
              display = "flex"
              flexWrap = "wrap"
              alignItems = "center"
              gap = "12px"
              padding = "4px 0"
            })(
              Fragment(formFields(editForm, editSubmit.isSubmitting, updateEdit)*),
              mui.Button
                .normal()("Save")
                .size("small")
                .variant("contained")
                .disabled(editSubmit.buttonDisabled(editForm.isValid))
                .`type`("submit")
                .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" }),
              mui.Button
                .normal()("Cancel")
                .size("small")
                .onClick { _ =>
                  editSubmit.reset()
                  setEditing(None)
                },
              submitError(editSubmit)
            )
          )
        case None if isLinkingExpense =>
          renderExpenseLinkPicker(r.id)
        case None =>
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"
            alignItems = "center"
            justifyContent = "space-between"
            width = "100%"
          })(
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "2px" })(
              mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "8px" })(
                mui
                  .Typography(r.amount.toString)
                  .sx(new CSSPropertiesUtils {
                    fontFamily = "\"IBM Plex Mono\", monospace"
                    fontSize = "14px"
                    fontWeight = "600"
                    color = "var(--lc-brand)"
                  }),
                mui
                  .Typography(r.date.toString)
                  .sx(new CSSPropertiesUtils {
                    fontFamily = "\"IBM Plex Mono\", monospace"
                    fontSize = "13px"
                    color = "var(--lc-text-2)"
                  })
              ),
              if (r.merchantName.isDefined || r.categoryName.isDefined)
                mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "8px" })(
                  r.merchantName match {
                    case Some(n) =>
                      (mui
                        .Typography(n)
                        .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" })
                        .build: ReactElement)
                    case None => Fragment()
                  },
                  r.categoryName match {
                    case Some(n) =>
                      (mui.Box.sx(new CSSPropertiesUtils {
                        backgroundColor = "var(--lc-tint)"
                        color = "var(--lc-brand-dark)"
                        fontSize = "11px"
                        fontWeight = "600"
                        padding = "2px 8px"
                        borderRadius = "4px"
                      })(
                        mui.Typography(n).sx(new CSSPropertiesUtils { fontSize = "11px"; fontWeight = "600" })
                      ): ReactElement)
                    case None => Fragment()
                  }
                )
              else Fragment(),
              r.expenseId match {
                case Some(eid) =>
                  (mui
                    .Typography(s"Linked to expense ${eid.toString.take(8)}…")
                    .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-link)" })
                    .build: ReactElement)
                case None => Fragment()
              },
              r.notes match {
                case Some(n) =>
                  (mui
                    .Typography(n)
                    .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" })
                    .build: ReactElement)
                case None => Fragment()
              }
            ),
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "4px"; alignItems = "center" })(
              mui.Button
                .normal()("Edit")
                .size("small")
                .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)" })
                .onClick { _ =>
                  editSubmit.reset()
                  setEditing(Some((r.id, RefundFormData.fromRefund(r))))
                },
              r.expenseId match {
                case Some(_) =>
                  mui.Button
                    .normal()("Unlink expense")
                    .size("small")
                    .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)" })
                    .onClick(_ => handleUnlinkFromExpense(r.id))
                case None =>
                  mui.Button
                    .normal()("Link expense")
                    .variant("outlined")
                    .size("small")
                    .onClick { _ =>
                      setLinkingExpenseForRefund(Some(r.id))
                      setExpenseFilter("")
                    }
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
      if (error.nonEmpty)
        mui
          .Typography(error)
          .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px"; marginBottom = "8px" })
      else Fragment(),
      mui.TextField
        .outlined()
        .label(s"Search refunds (${filteredRefunds.size})")
        .size("small")
        .value(searchText)
        .onChange(e => { setSearchText(e.target.asInstanceOf[dom.HTMLInputElement].value); setPage(0) })
        .sx(new CSSPropertiesUtils { width = "100%"; marginBottom = "12px" }),
      Fragment(refundItems*),
      paginationWidget
    )
  }
}
