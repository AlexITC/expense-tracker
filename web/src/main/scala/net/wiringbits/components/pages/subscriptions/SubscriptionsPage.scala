package net.wiringbits.components.pages.subscriptions

import com.olvind.mui.muiMaterial.autocompleteAutocompleteMod.AutocompleteRenderInputParams
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.subscriptions.{GetSubscriptions, SubscriptionCadence, SubscriptionPaymentMethod}
import net.wiringbits.components.pages.AutocompleteLabels
import net.wiringbits.components.widgets.Loader
import net.wiringbits.forms.{AmountMode, SubscriptionFormData}
import net.wiringbits.models.User
import net.wiringbits.ui.components.inputs.SubscriptionNameInput
import net.wiringbits.ui.forms.SubmitHooks
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.compat.MuiCompat
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import net.wiringbits.webapp.utils.slinkyUtils.core.GenericHooks
import net.wiringbits.webapp.utils.slinkyUtils.forms.StatefulFormData
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage, SyntheticEvent}
import slinky.web.html.{form, onSubmit}

import java.time.LocalDate
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object SubscriptionsPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (timesRefreshing, forceRefresh) = GenericHooks.useForceRefresh

    mui.Box.sx(new CSSPropertiesUtils { padding = "24px"; maxWidth = "800px" })(
      AsyncComponent[GetSubscriptions.Response](
        fetch = () => props.ctx.api.client.getSubscriptions,
        render = subscriptions =>
          AsyncComponent[GetCategories.Response](
            fetch = () => props.ctx.api.client.getCategories,
            render = categories => SubscriptionList(props.ctx, subscriptions, categories, () => forceRefresh()),
            progressIndicator = () => Loader(props.ctx),
            watchedObjects = List(timesRefreshing)
          ),
        progressIndicator = () => Loader(props.ctx),
        watchedObjects = List(timesRefreshing)
      )
    )
  }
}

object SubscriptionList {
  def apply(
      ctx: AppContext,
      subscriptions: GetSubscriptions.Response,
      categories: GetCategories.Response,
      onRefresh: () => Unit
  ): KeyAddingStage =
    component(Props(ctx, subscriptions, categories, onRefresh))

  case class Props(
      ctx: AppContext,
      subscriptions: GetSubscriptions.Response,
      categories: GetCategories.Response,
      onRefresh: () => Unit
  )

  private val NameLabel = "Subscription name"

  private def emptyForm: SubscriptionFormData = SubscriptionFormData.initial(NameLabel)

  private val cardStyle = new CSSPropertiesUtils {
    border = "1px solid var(--lc-border)"
    borderRadius = "8px"
    backgroundColor = "var(--lc-paper)"
    marginBottom = "6px"
    padding = "12px 16px"
    width = "100%"
  }

  // Free-solo autocompletes are not full width, so without a minimum they collapse to the typed text.
  private val autocompleteFieldStyle = new CSSPropertiesUtils { minWidth = "200px" }

  private def renderCategoryTextField(params: AutocompleteRenderInputParams): ReactElement =
    (mui.TextField.outlined
      .withProps(MuiCompat.textFieldProps(params, "Category (optional)", size = Some("small")))
      .sx(autocompleteFieldStyle)
      .withKey("tf"): ReactElement)

  private def statusChip(status: SubscriptionStatus): ReactElement = {
    val (bg, fg) = status match {
      case SubscriptionStatus.Upcoming => ("var(--lc-tint)", "var(--lc-brand-dark)")
      case SubscriptionStatus.Due => ("var(--lc-warm-bg)", "var(--lc-warning-text)")
      case SubscriptionStatus.Overdue => ("var(--lc-badge-error-bg)", "var(--lc-error)")
    }
    mui.Chip
      .label(status.label)
      .size("small")
      .sx(new CSSPropertiesUtils {
        height = "18px"; fontSize = "11px"; backgroundColor = bg; color = fg
      })
  }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (createFormData, setCreateFormData) = Hooks.useState(StatefulFormData(emptyForm))
    val (editingId, setEditingId) = Hooks.useState[Option[UUID]](None)
    val (editFormData, setEditFormData) = Hooks.useState(StatefulFormData(emptyForm))
    val createControl = SubmitHooks.useSubmit()
    val editControl = SubmitHooks.useSubmit()
    val deleteControl = SubmitHooks.useSubmit()

    val categoryNames: Map[UUID, String] = props.categories.data.map(c => c.id -> c.name).toMap
    val today = LocalDate.now()

    def onCreateDataChanged(f: SubscriptionFormData => SubscriptionFormData): Unit = {
      createControl.clearError()
      setCreateFormData(cur => cur.filling.copy(data = f(cur.data)))
    }

    def onEditDataChanged(f: SubscriptionFormData => SubscriptionFormData): Unit = {
      editControl.clearError()
      setEditFormData(cur => cur.filling.copy(data = f(cur.data)))
    }

    def handleCreate(e: SyntheticEvent[?, dom.Event]): Unit = {
      e.preventDefault()
      createFormData.data.submitRequest.foreach { request =>
        createControl.begin()
        props.ctx.api.client
          .createSubscription(request)
          .onComplete {
            case Success(_) =>
              createControl.reset()
              setCreateFormData(_ => StatefulFormData(emptyForm))
              props.onRefresh()
            case Failure(ex) => createControl.fail(ex.getMessage)
          }
      }
    }

    def handleUpdate(subscriptionId: UUID)(e: SyntheticEvent[?, dom.Event]): Unit = {
      e.preventDefault()
      editFormData.data.updateRequest.foreach { request =>
        editControl.begin()
        props.ctx.api.client
          .updateSubscription(subscriptionId, request)
          .onComplete {
            case Success(_) =>
              editControl.succeed()
              setEditingId(None)
              props.onRefresh()
            case Failure(ex) => editControl.fail(ex.getMessage)
          }
      }
    }

    def handleDelete(subscriptionId: UUID): Unit = {
      deleteControl.begin()
      props.ctx.api.client
        .deleteSubscription(subscriptionId)
        .onComplete {
          case Success(_) =>
            deleteControl.reset()
            props.onRefresh()
          case Failure(ex) => deleteControl.fail(ex.getMessage)
        }
    }

    def startEditing(s: GetSubscriptions.Response.Subscription): Unit = {
      editControl.reset()
      setEditFormData(_ =>
        StatefulFormData(
          SubscriptionFormData.fromSubscription(
            nameLabel = NameLabel,
            subscription = s,
            categoryName = s.categoryId.flatMap(categoryNames.get)
          )
        )
      )
      setEditingId(Some(s.id))
    }

    def textField(
        key: String,
        label: String,
        value: String,
        disabled: Boolean,
        onChange: String => Unit
    ): ReactElement =
      mui.TextField
        .outlined()
        .label(label)
        .value(value)
        .size("small")
        .disabled(disabled)
        .onChange(e => { val v = e.target.asInstanceOf[dom.HTMLInputElement].value; onChange(v) })
        .withKey(key)

    def selectField(
        key: String,
        label: String,
        value: String,
        disabled: Boolean,
        options: Seq[(String, String)],
        onChange: String => Unit
    ): ReactElement = {
      val items = options.map { case (entryName, optionLabel) =>
        mui.MenuItem.normal()(optionLabel).value(entryName).withKey(entryName).build
      }
      mui.TextField
        .outlined()
        .select(true)
        .label(label)
        .value(value)
        .size("small")
        .disabled(disabled)
        .onChange(e => { val v = e.target.asInstanceOf[dom.HTMLInputElement].value; onChange(v) })
        .sx(new CSSPropertiesUtils { minWidth = "160px" })(items*)
        .withKey(key)
    }

    def categoryAutocomplete(
        data: SubscriptionFormData,
        disabled: Boolean,
        update: (SubscriptionFormData => SubscriptionFormData) => Unit
    ): ReactElement = {
      val acProps = MuiCompat.freeSoloAutocompleteProps(
        options = props.categories.data,
        renderInput = renderCategoryTextField,
        inputValue = data.categoryInput,
        getOptionLabel = AutocompleteLabels.categoryOptionLabel,
        onSelect = value =>
          (value: Any) match {
            case c: GetCategories.Response.Category =>
              update(_.copy(categoryId = Some(c.id), categoryInput = c.name))
            case _ => ()
          },
        onInput = (text, rsn) =>
          if (rsn == "reset") update(_.copy(categoryInput = text))
          else update(_.copy(categoryInput = text, categoryId = None)),
        disabled = disabled,
        fullWidth = false
      )
      (mui.Autocomplete.withProps(acProps).withKey("category-ac"): ReactElement)
    }

    def formFields(
        data: SubscriptionFormData,
        disabled: Boolean,
        update: (SubscriptionFormData => SubscriptionFormData) => Unit
    ): Seq[ReactElement] = {
      val intervalField: Seq[ReactElement] =
        if (data.cadence == SubscriptionCadence.EveryNDays)
          Seq(
            textField(
              key = "interval",
              label = "Interval (days)",
              value = data.intervalDays,
              disabled = disabled,
              onChange = v => update(_.copy(intervalDays = v))
            )
          )
        else Seq.empty

      val amountFields: Seq[ReactElement] = data.amountMode match {
        case AmountMode.Exact =>
          Seq(
            textField(
              key = "exact-amount",
              label = "Amount (optional)",
              value = data.exactAmount,
              disabled = disabled,
              onChange = v => update(_.copy(exactAmount = v))
            )
          )
        case AmountMode.Range =>
          Seq(
            textField(
              key = "min-amount",
              label = "Minimum amount",
              value = data.minAmount,
              disabled = disabled,
              onChange = v => update(_.copy(minAmount = v))
            ),
            textField(
              key = "max-amount",
              label = "Maximum amount",
              value = data.maxAmount,
              disabled = disabled,
              onChange = v => update(_.copy(maxAmount = v))
            )
          )
      }

      Seq[ReactElement](
        mui.Box
          .sx(new CSSPropertiesUtils { minWidth = "240px" })(
            SubscriptionNameInput.component(
              SubscriptionNameInput.Props(
                data.name,
                disabled = disabled,
                onChange = value => update(x => x.copy(name = x.name.updated(value)))
              )
            )
          )
          .withKey("name"),
        selectField(
          key = "cadence",
          label = "Cadence",
          value = data.cadence.entryName,
          disabled = disabled,
          options =
            SubscriptionCadence.values.toSeq.map(c => c.entryName -> SubscriptionStatusLogic.cadenceLabel(c, None)),
          onChange = v => update(_.copy(cadence = SubscriptionCadence.fromEntryName(v)))
        )
      ) ++ intervalField ++ Seq[ReactElement](
        selectField(
          key = "amount-mode",
          label = "Amount",
          value = data.amountMode.toString,
          disabled = disabled,
          options = AmountMode.values.toSeq.map(m => m.toString -> m.toString),
          onChange = v => update(_.copy(amountMode = AmountMode.valueOf(v)))
        )
      ) ++ amountFields ++ Seq[ReactElement](
        selectField(
          key = "payment-method",
          label = "Payment method",
          value = data.paymentMethod.entryName,
          disabled = disabled,
          options = SubscriptionPaymentMethod.values.toSeq.map(m =>
            m.entryName -> SubscriptionStatusLogic.paymentMethodLabel(m)
          ),
          onChange = v => update(_.copy(paymentMethod = SubscriptionPaymentMethod.fromEntryName(v)))
        ),
        categoryAutocomplete(data, disabled, update),
        textField(
          key = "start-date",
          label = "Start date (YYYY-MM-DD)",
          value = data.startDate,
          disabled = disabled,
          onChange = v => update(_.copy(startDate = v))
        )
      )
    }

    def errorText(key: String, text: Option[String]): ReactElement =
      text match {
        case Some(t) =>
          mui
            .Typography(t)
            .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px"; width = "100%" })
            .withKey(key)
        case None => Fragment().withKey(key)
      }

    def createForm: ReactElement =
      form(onSubmit := (handleCreate(_)))(
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
          Fragment(formFields(createFormData.data, createControl.inputDisabled, onCreateDataChanged)*),
          errorText("error", createFormData.firstValidationError.orElse(createControl.error)),
          mui.Button
            .normal()("Add subscription")
            .variant("contained")
            .`type`("submit")
            .disabled(createControl.buttonDisabled(createFormData.data.submitRequest.isDefined))
            .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)"; borderRadius = "8px" })
        )
      )

    def editForm(subscriptionId: UUID): ReactElement =
      form(onSubmit := (handleUpdate(subscriptionId)(_)))(
        mui.Box.sx(new CSSPropertiesUtils {
          display = "flex"
          flexWrap = "wrap"
          alignItems = "center"
          gap = "12px"
          padding = "4px 0"
        })(
          Fragment(formFields(editFormData.data, editControl.inputDisabled, onEditDataChanged)*),
          errorText("error", editFormData.firstValidationError.orElse(editControl.error)),
          mui.Button
            .normal()("Save")
            .size("small")
            .variant("contained")
            .`type`("submit")
            .disabled(editControl.buttonDisabled(editFormData.data.updateRequest.isDefined))
            .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" }),
          mui.Button
            .normal()("Cancel")
            .size("small")
            .disabled(editControl.inputDisabled)
            .onClick(_ => setEditingId(None))
        )
      )

    def details(s: GetSubscriptions.Response.Subscription): String =
      List(
        Some(SubscriptionStatusLogic.cadenceLabel(s.cadence, s.intervalDays)),
        Some(SubscriptionStatusLogic.amountLabel(amountMin = s.amountMin, amountMax = s.amountMax)).filter(_.nonEmpty),
        Some(SubscriptionStatusLogic.paymentMethodLabel(s.paymentMethod)),
        s.categoryId.flatMap(categoryNames.get),
        Some(s"Since ${s.startDate}")
      ).flatten.mkString(" · ")

    def summary(s: GetSubscriptions.Response.Subscription): ReactElement =
      mui.Box.sx(new CSSPropertiesUtils {
        display = "flex"
        alignItems = "center"
        justifyContent = "space-between"
        width = "100%"
      })(
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "2px" })(
          mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "6px" })(
            mui
              .Typography(s.name)
              .sx(new CSSPropertiesUtils { fontSize = "14px"; fontWeight = "600"; color = "var(--lc-text)" }),
            SubscriptionStatusLogic
              .status(startDate = s.startDate, cadence = s.cadence, intervalDays = s.intervalDays, today = today)
              .fold(_ => Fragment(), statusChip)
          ),
          mui
            .Typography(details(s))
            .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" })
        ),
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "4px" })(
          mui.Button
            .normal()("Edit")
            .size("small")
            .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)" })
            .onClick(_ => startEditing(s)),
          mui.Button
            .normal()("Delete")
            .size("small")
            .disabled(deleteControl.isSubmitting)
            .sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
            .onClick(_ => handleDelete(s.id))
        )
      )

    val items: Seq[ReactElement] = props.subscriptions.data.map { s =>
      val content = if (editingId.contains(s.id)) editForm(s.id) else summary(s)
      mui.Box.sx(cardStyle)(content).withKey(s.id.toString)
    }

    Fragment(
      createForm,
      deleteControl.error match {
        case Some(msg) =>
          mui
            .Typography(msg)
            .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px"; marginBottom = "8px" })
        case None => Fragment()
      },
      Fragment(items*)
    )
  }
}
