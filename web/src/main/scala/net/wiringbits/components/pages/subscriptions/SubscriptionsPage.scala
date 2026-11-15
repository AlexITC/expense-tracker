package net.wiringbits.components.pages.subscriptions

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.models.subscriptions.{
  GetSubscriptions,
  SubscriptionCadence,
  SubscriptionExtraChargeRecurrence,
  SubscriptionPaymentMethod,
  SubscriptionTerm
}
import net.wiringbits.common.models.MerchantName
import net.wiringbits.components.pages.MerchantResolver
import net.wiringbits.core.SafeHooks
import net.wiringbits.components.widgets.Loader
import net.wiringbits.forms.{
  AmountMode,
  CreateSubscriptionFormData,
  SubscriptionAmountFields,
  SubscriptionCancelFormData,
  SubscriptionExtraChargeFormData,
  SubscriptionFormData,
  SubscriptionPriceChangeFormData,
  SubscriptionResumeFormData
}
import net.wiringbits.models.User
import net.wiringbits.ui.components.inputs.{
  DateInput,
  OptionalIdSelectInput,
  OptionalMerchantAutocompleteInput,
  RequiredSubscriptionAmountInput,
  SubscriptionAmountInput,
  SubscriptionDescriptionInput,
  SubscriptionExtraChargeLabelInput,
  SubscriptionIntervalDaysInput,
  SubscriptionIntervalMonthsInput,
  SubscriptionNameInput
}
import net.wiringbits.ui.forms.{
  ExistingOrNewValue,
  ExpLocalDate,
  SubmitHooks,
  SubscriptionAmount,
  SubscriptionIntervalDays,
  SubscriptionIntervalMonths
}
import net.wiringbits.webapp.utils.slinkyUtils.forms.FormData
import net.wiringbits.webapp.common.validators.ValidationResult
import net.wiringbits.webapp.utils.slinkyUtils.forms.FormField
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import net.wiringbits.webapp.utils.slinkyUtils.core.GenericHooks
import net.wiringbits.webapp.utils.slinkyUtils.forms.StatefulFormData
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage, SyntheticEvent}
import slinky.web.html.{form, onSubmit}

import java.time.LocalDate
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
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
            render = categories =>
              AsyncComponent[GetMerchants.Response](
                fetch = () => props.ctx.api.client.getMerchants,
                render =
                  merchants => SubscriptionList(props.ctx, subscriptions, categories, merchants, () => forceRefresh()),
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

/** The inline editor open on the page; only one can be open at a time. */
private enum SubscriptionEditor {
  case Closed
  case Edit(subscriptionId: UUID)
  case PriceChange(subscriptionId: UUID)
  case Cancel(subscriptionId: UUID)
  case Resume(subscriptionId: UUID)
  case AddExtraCharge(subscriptionId: UUID)
}

/** The start-date suggestion fetched for the create form's merchant, tagged with that merchant so a response that lands
  * after the merchant changed is never shown for the new one.
  */
private enum StartDateSuggestion {
  case NotRequested
  case Found(merchantId: UUID, earliestExpenseDate: LocalDate)
  case NoExpenses(merchantId: UUID)
}

object SubscriptionList {
  def apply(
      ctx: AppContext,
      subscriptions: GetSubscriptions.Response,
      categories: GetCategories.Response,
      merchants: GetMerchants.Response,
      onRefresh: () => Unit
  ): KeyAddingStage =
    component(Props(ctx, subscriptions, categories, merchants, onRefresh))

  case class Props(
      ctx: AppContext,
      subscriptions: GetSubscriptions.Response,
      categories: GetCategories.Response,
      merchants: GetMerchants.Response,
      onRefresh: () => Unit
  )

  private val NameLabel = "Subscription name"

  private def emptyForm: CreateSubscriptionFormData = CreateSubscriptionFormData.initial(NameLabel)

  private val cardStyle = new CSSPropertiesUtils {
    border = "1px solid var(--lc-border)"
    borderRadius = "8px"
    backgroundColor = "var(--lc-paper)"
    marginBottom = "6px"
    padding = "12px 16px"
    width = "100%"
  }

  private def statusChip(status: SubscriptionStatus): ReactElement = {
    val (bg, fg) = status match {
      case SubscriptionStatus.Upcoming => ("var(--lc-tint)", "var(--lc-brand-dark)")
      case SubscriptionStatus.Due => ("var(--lc-warm-bg)", "var(--lc-warning-text)")
      case SubscriptionStatus.Overdue => ("var(--lc-badge-error-bg)", "var(--lc-error)")
      case SubscriptionStatus.Cancelled => ("var(--lc-border)", "var(--lc-text-2)")
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
    // Validation errors stay hidden until the user edits the create form, so a fresh form isn't shown as invalid
    val (createTouched, setCreateTouched) = Hooks.useState(false)
    val (editor, setEditor) = Hooks.useState[SubscriptionEditor](SubscriptionEditor.Closed)
    val (editFormData, setEditFormData) = Hooks.useState(StatefulFormData(emptyForm.details))
    val (priceChangeFormData, setPriceChangeFormData) =
      Hooks.useState(StatefulFormData(SubscriptionPriceChangeFormData.initial))
    val (cancelFormData, setCancelFormData) = Hooks.useState(StatefulFormData(SubscriptionCancelFormData.initial))
    val (resumeFormData, setResumeFormData) = Hooks.useState(StatefulFormData(SubscriptionResumeFormData.initial))
    val (extraChargeFormData, setExtraChargeFormData) =
      Hooks.useState(StatefulFormData(SubscriptionExtraChargeFormData.initial))
    val createControl = SubmitHooks.useSubmit()
    val (startDateSuggestion, setStartDateSuggestion) =
      Hooks.useState[StartDateSuggestion](StartDateSuggestion.NotRequested)
    val suggestionControl = SubmitHooks.useSubmit()
    // One control serves every inline editor, since only one is open at a time.
    val editControl = SubmitHooks.useSubmit()
    val deleteControl = SubmitHooks.useSubmit()

    val categoryNames: Map[UUID, String] = props.categories.data.map(c => c.id -> c.name).toMap
    val categoryOptions: Seq[(String, String)] = props.categories.data.map(c => c.id.toString -> c.name)
    val merchantNames: Map[UUID, String] = props.merchants.data.map(m => m.id -> m.name).toMap

    // Picks or creates the selected merchant (a new one takes the subscription's category as its default), then
    // sends the request built with its id. A newly created merchant is reported through `onCreated` before sending,
    // so the form can point at it and a retry after a failed send reuses it instead of creating it again.
    def withResolvedMerchant[R](
        selection: Option[ExistingOrNewValue[MerchantName, UUID]],
        categoryId: Option[UUID],
        onCreated: GetMerchants.Response.Merchant => Unit
    )(send: Option[UUID] => Future[R]): Future[R] =
      MerchantResolver
        .findOrCreateMerchantOpt(props.ctx.api.client, props.merchants.data, selection, categoryId)
        .flatMap { merchant =>
          merchant.flatMap(_._2).foreach(onCreated)
          send(merchant.map(_._1))
        }
    val today = LocalDate.now()

    val pickedMerchantId: Option[UUID] = createFormData.data.details.existingMerchantId
    SafeHooks.useEffect(pickedMerchantId.fold("")(_.toString)) { () =>
      setStartDateSuggestion(_ => StartDateSuggestion.NotRequested)
      suggestionControl.reset()
    }

    def onCreateDataChanged(f: CreateSubscriptionFormData => CreateSubscriptionFormData): Unit = {
      createControl.clearError()
      setCreateTouched(true)
      setCreateFormData(cur => cur.filling.copy(data = f(cur.data)))
    }

    def editorUpdater[T <: FormData[?]](
        setFormData: (StatefulFormData[T] => StatefulFormData[T]) => Unit
    ): (T => T) => Unit = f => {
      editControl.clearError()
      setFormData(cur => cur.filling.copy(data = f(cur.data)))
    }

    val onEditDataChanged = editorUpdater(setEditFormData)
    val onPriceChangeDataChanged = editorUpdater(setPriceChangeFormData)
    val onCancelDataChanged = editorUpdater(setCancelFormData)
    val onResumeDataChanged = editorUpdater(setResumeFormData)
    val onExtraChargeDataChanged = editorUpdater(setExtraChargeFormData)

    def handleCreate(e: SyntheticEvent[?, dom.Event]): Unit = {
      e.preventDefault()
      createFormData.data.submitRequest.foreach { resolved =>
        createControl.begin()
        withResolvedMerchant(
          resolved.details.merchant,
          resolved.details.categoryId,
          onCreated = created =>
            setCreateFormData(cur =>
              cur.copy(data = cur.data.copy(details = cur.data.details.withExistingMerchant(created)))
            )
        )(merchantId => props.ctx.api.client.createSubscription(resolved.request(merchantId)))
          .onComplete {
            case Success(_) =>
              createControl.reset()
              setCreateFormData(_ => StatefulFormData(emptyForm))
              setCreateTouched(false)
              props.onRefresh()
            case Failure(ex) => createControl.fail(ex.getMessage)
          }
      }
    }

    // Submits an inline editor's request, closing the editor and refreshing the list on success.
    def submitEditor[R](request: Option[R], send: R => Future[?])(
        e: SyntheticEvent[?, dom.Event]
    ): Unit = {
      e.preventDefault()
      request.foreach { r =>
        editControl.begin()
        send(r).onComplete {
          case Success(_) =>
            editControl.succeed()
            setEditor(_ => SubscriptionEditor.Closed)
            props.onRefresh()
          case Failure(ex) => editControl.fail(ex.getMessage)
        }
      }
    }

    def handleSuggestStartDate(merchantId: UUID): Unit = {
      suggestionControl.begin()
      props.ctx.api.client
        .getSubscriptionStartDateSuggestion(merchantId)
        .onComplete {
          case Success(response) =>
            suggestionControl.succeed()
            val suggestion = response.earliestExpenseDate match {
              case Some(date) => StartDateSuggestion.Found(merchantId = merchantId, earliestExpenseDate = date)
              case None => StartDateSuggestion.NoExpenses(merchantId)
            }
            setStartDateSuggestion(_ => suggestion)
          case Failure(ex) => suggestionControl.fail(ex.getMessage)
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

    def handleDeleteExtraCharge(subscriptionId: UUID, extraChargeId: UUID): Unit = {
      deleteControl.begin()
      props.ctx.api.client
        .deleteSubscriptionExtraCharge(subscriptionId = subscriptionId, extraChargeId = extraChargeId)
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
            merchants = props.merchants.data
          )
        )
      )
      setEditor(_ => SubscriptionEditor.Edit(s.id))
    }

    def startPriceChange(subscriptionId: UUID): Unit = {
      editControl.reset()
      setPriceChangeFormData(_ => StatefulFormData(SubscriptionPriceChangeFormData.initial))
      setEditor(_ => SubscriptionEditor.PriceChange(subscriptionId))
    }

    def startCancel(subscriptionId: UUID): Unit = {
      editControl.reset()
      setCancelFormData(_ => StatefulFormData(SubscriptionCancelFormData.initial))
      setEditor(_ => SubscriptionEditor.Cancel(subscriptionId))
    }

    def startResume(subscriptionId: UUID): Unit = {
      editControl.reset()
      setResumeFormData(_ => StatefulFormData(SubscriptionResumeFormData.initial))
      setEditor(_ => SubscriptionEditor.Resume(subscriptionId))
    }

    def startAddExtraCharge(subscriptionId: UUID): Unit = {
      editControl.reset()
      setExtraChargeFormData(_ => StatefulFormData(SubscriptionExtraChargeFormData.initial))
      setEditor(_ => SubscriptionEditor.AddExtraCharge(subscriptionId))
    }

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

    // The validated inputs are full width, so each sits in a box that sets its width within the wrapping row.
    def fieldBox(key: String, boxMinWidth: String)(input: ReactElement): ReactElement =
      mui.Box.sx(new CSSPropertiesUtils { minWidth = boxMinWidth })(input).withKey(key)

    def amountInput(
        key: String,
        field: FormField[Option[SubscriptionAmount]],
        disabled: Boolean,
        onChange: ValidationResult[Option[SubscriptionAmount]] => Unit
    ): ReactElement =
      fieldBox(key = key, boxMinWidth = "160px")(
        SubscriptionAmountInput.component(
          SubscriptionAmountInput.Props(field = field, disabled = disabled, onChange = onChange)
        )
      )

    def formFields(
        data: SubscriptionFormData,
        disabled: Boolean,
        update: (SubscriptionFormData => SubscriptionFormData) => Unit
    ): Seq[ReactElement] = {
      val intervalField: Seq[ReactElement] = data.cadence match {
        case SubscriptionCadence.EveryNDays =>
          Seq(
            fieldBox(key = "interval", boxMinWidth = "140px")(
              SubscriptionIntervalDaysInput.component(
                SubscriptionIntervalDaysInput.Props(
                  field = data.intervalDays,
                  disabled = disabled,
                  onChange = (value: ValidationResult[SubscriptionIntervalDays]) =>
                    update(x => x.copy(intervalDays = x.intervalDays.updated(value)))
                )
              )
            )
          )
        case SubscriptionCadence.EveryNMonths =>
          Seq(
            fieldBox(key = "interval-months", boxMinWidth = "140px")(
              SubscriptionIntervalMonthsInput.component(
                SubscriptionIntervalMonthsInput.Props(
                  field = data.intervalMonths,
                  disabled = disabled,
                  onChange = (value: ValidationResult[SubscriptionIntervalMonths]) =>
                    update(x => x.copy(intervalMonths = x.intervalMonths.updated(value)))
                )
              )
            )
          )
        case SubscriptionCadence.Monthly | SubscriptionCadence.Yearly => Seq.empty
      }

      Seq[ReactElement](
        fieldBox(key = "name", boxMinWidth = "240px")(
          SubscriptionNameInput.component(
            SubscriptionNameInput.Props(
              data.name,
              disabled = disabled,
              onChange = value => update(x => x.copy(name = x.name.updated(value)))
            )
          )
        ),
        selectField(
          key = "cadence",
          label = "Cadence",
          value = data.cadence.entryName,
          disabled = disabled,
          options = SubscriptionCadence.values.toSeq.map(c =>
            c.entryName -> SubscriptionStatusLogic.cadenceLabel(c, intervalDays = None, intervalMonths = None)
          ),
          onChange = v => update(_.copy(cadence = SubscriptionCadence.fromEntryName(v)))
        )
      ) ++ intervalField ++ Seq[ReactElement](
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
        fieldBox(key = "merchant", boxMinWidth = "220px")(
          OptionalMerchantAutocompleteInput.component(
            OptionalMerchantAutocompleteInput.Props(
              field = data.merchant,
              options = props.merchants.data,
              label = "Merchant (optional)",
              onChange = value => update(x => x.copy(merchant = x.merchant.updated(value))),
              disabled = disabled
            )
          )
        ),
        fieldBox(key = "category", boxMinWidth = "200px")(
          OptionalIdSelectInput.component(
            OptionalIdSelectInput.Props(
              field = data.categoryId,
              options = categoryOptions,
              disabled = disabled,
              onChange = value => update(x => x.copy(categoryId = x.categoryId.updated(value)))
            )
          )
        ),
        fieldBox(key = "description", boxMinWidth = "240px")(
          SubscriptionDescriptionInput.component(
            SubscriptionDescriptionInput.Props(
              field = data.description,
              disabled = disabled,
              onChange = value => update(x => x.copy(description = x.description.updated(value)))
            )
          )
        )
      )
    }

    def amountFields(
        data: SubscriptionAmountFields,
        disabled: Boolean,
        update: (SubscriptionAmountFields => SubscriptionAmountFields) => Unit
    ): Seq[ReactElement] = {
      val inputs: Seq[ReactElement] = data.mode match {
        case AmountMode.Exact =>
          Seq[ReactElement](
            amountInput(
              key = "exact-amount",
              field = data.exact,
              disabled = disabled,
              onChange = value => update(x => x.copy(exact = x.exact.updated(value)))
            )
          )
        case AmountMode.Range =>
          Seq[ReactElement](
            amountInput(
              key = "min-amount",
              field = data.min,
              disabled = disabled,
              onChange = value => update(x => x.copy(min = x.min.updated(value)))
            ),
            amountInput(
              key = "max-amount",
              field = data.max,
              disabled = disabled,
              onChange = value => update(x => x.copy(max = x.max.updated(value)))
            )
          )
      }

      Seq[ReactElement](
        selectField(
          key = "amount-mode",
          label = "Amount",
          value = data.mode.toString,
          disabled = disabled,
          options = AmountMode.values.toSeq.map(m => m.toString -> m.toString),
          onChange = v => update(_.copy(mode = AmountMode.valueOf(v)))
        )
      ) ++ inputs
    }

    def dateField(
        key: String,
        field: FormField[ExpLocalDate],
        disabled: Boolean,
        onChange: ValidationResult[ExpLocalDate] => Unit
    ): ReactElement =
      fieldBox(key = key, boxMinWidth = "200px")(
        DateInput.component(DateInput.Props(field = field, disabled = disabled, onChange = onChange))
      )

    // The amount and start date set the first term, so only the create form has them.
    def firstTermFields(
        data: CreateSubscriptionFormData,
        disabled: Boolean,
        update: (CreateSubscriptionFormData => CreateSubscriptionFormData) => Unit
    ): Seq[ReactElement] =
      amountFields(data.amounts, disabled, f => update(x => x.copy(amounts = f(x.amounts)))) :+
        dateField(
          key = "start-date",
          field = data.startDate,
          disabled = disabled,
          onChange = value => update(x => x.copy(startDate = x.startDate.updated(value)))
        )

    def errorText(key: String, text: Option[String]): ReactElement =
      text match {
        case Some(t) =>
          mui
            .Typography(t)
            .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px"; width = "100%" })
            .withKey(key)
        case None => Fragment().withKey(key)
      }

    // Only an explicit "Use this date" click writes the suggested date into the form.
    def startDateSuggestionRow(merchantId: UUID): ReactElement = {
      val hint = new CSSPropertiesUtils { color = "var(--lc-text-2)"; fontSize = "13px" }
      val result: ReactElement = startDateSuggestion match {
        case StartDateSuggestion.NotRequested => Fragment()
        case StartDateSuggestion.Found(id, date) =>
          if (id == merchantId)
            Fragment(
              mui.Typography(s"Earliest expense: $date").sx(hint),
              mui.Button
                .normal()("Use this date")
                .size("small")
                .disabled(createControl.inputDisabled)
                .onClick(_ => onCreateDataChanged(_.withSuggestedStartDate(date)))
            )
          else Fragment()
        case StartDateSuggestion.NoExpenses(id) =>
          if (id == merchantId) mui.Typography("No expenses found for this merchant").sx(hint)
          else Fragment()
      }
      mui.Box
        .sx(new CSSPropertiesUtils {
          display = "flex"; alignItems = "center"; gap = "12px"; flexWrap = "wrap"; width = "100%"
        })(
          mui.Button
            .normal()("Suggest start date")
            .size("small")
            .variant("outlined")
            .disabled(suggestionControl.isSubmitting || createControl.inputDisabled)
            .onClick(_ => handleSuggestStartDate(merchantId)),
          result,
          errorText("suggestion-error", suggestionControl.error)
        )
        .withKey("start-date-suggestion")
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
          Fragment(
            formFields(
              createFormData.data.details,
              createControl.inputDisabled,
              f => onCreateDataChanged(x => x.copy(details = f(x.details)))
            ) ++ firstTermFields(createFormData.data, createControl.inputDisabled, onCreateDataChanged) ++
              pickedMerchantId.map(startDateSuggestionRow).toSeq*
          ),
          errorText(
            "error",
            createFormData.firstValidationError.filter(_ => createTouched).orElse(createControl.error)
          ),
          mui.Button
            .normal()("Add subscription")
            .variant("contained")
            .`type`("submit")
            .disabled(createControl.buttonDisabled(createFormData.data.isValid))
            .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)"; borderRadius = "8px" })
        )
      )

    def inlineEditor(
        inputs: Seq[ReactElement],
        validationError: Option[String],
        submitLabel: String,
        canSubmit: Boolean,
        onFormSubmit: SyntheticEvent[?, dom.Event] => Unit
    ): ReactElement =
      form(onSubmit := onFormSubmit)(
        mui.Box.sx(new CSSPropertiesUtils {
          display = "flex"
          flexWrap = "wrap"
          alignItems = "center"
          gap = "12px"
          padding = "4px 0"
        })(
          Fragment(inputs*),
          errorText("error", validationError.orElse(editControl.error)),
          mui.Button
            .normal()(submitLabel)
            .size("small")
            .variant("contained")
            .`type`("submit")
            .disabled(editControl.buttonDisabled(canSubmit))
            .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" }),
          mui.Button
            .normal()("Close")
            .size("small")
            .disabled(editControl.inputDisabled)
            .onClick(_ => setEditor(_ => SubscriptionEditor.Closed))
        )
      )

    def editForm(subscriptionId: UUID): ReactElement =
      inlineEditor(
        inputs = formFields(editFormData.data, editControl.inputDisabled, onEditDataChanged),
        validationError = editFormData.firstValidationError,
        submitLabel = "Save",
        canSubmit = editFormData.data.isValid,
        onFormSubmit = submitEditor(
          editFormData.data.submitRequest,
          resolved =>
            withResolvedMerchant(
              resolved.merchant,
              resolved.categoryId,
              onCreated = created => setEditFormData(cur => cur.copy(data = cur.data.withExistingMerchant(created)))
            )(merchantId => props.ctx.api.client.updateSubscription(subscriptionId, resolved.request(merchantId)))
        )
      )

    def priceChangeForm(subscriptionId: UUID): ReactElement = {
      val data = priceChangeFormData.data
      val disabled = editControl.inputDisabled
      inlineEditor(
        inputs = dateField(
          key = "effective-from",
          field = data.effectiveFrom,
          disabled = disabled,
          onChange = value => onPriceChangeDataChanged(x => x.copy(effectiveFrom = x.effectiveFrom.updated(value)))
        ) +: amountFields(
          data.amounts,
          disabled,
          f => onPriceChangeDataChanged(x => x.copy(amounts = f(x.amounts)))
        ),
        validationError = priceChangeFormData.firstValidationError,
        submitLabel = "Change price",
        canSubmit = data.isValid,
        onFormSubmit = submitEditor(data.submitRequest, props.ctx.api.client.changeSubscriptionPrice(subscriptionId, _))
      )
    }

    def cancelForm(subscriptionId: UUID): ReactElement = {
      val data = cancelFormData.data
      inlineEditor(
        inputs = Seq(
          dateField(
            key = "effective-to",
            field = data.effectiveTo,
            disabled = editControl.inputDisabled,
            onChange = value => onCancelDataChanged(x => x.copy(effectiveTo = x.effectiveTo.updated(value)))
          )
        ),
        validationError = cancelFormData.firstValidationError,
        submitLabel = "Cancel subscription",
        canSubmit = data.isValid,
        onFormSubmit = submitEditor(data.submitRequest, props.ctx.api.client.cancelSubscription(subscriptionId, _))
      )
    }

    def resumeForm(subscriptionId: UUID): ReactElement = {
      val data = resumeFormData.data
      val disabled = editControl.inputDisabled
      inlineEditor(
        inputs = dateField(
          key = "effective-from",
          field = data.effectiveFrom,
          disabled = disabled,
          onChange = value => onResumeDataChanged(x => x.copy(effectiveFrom = x.effectiveFrom.updated(value)))
        ) +: amountFields(data.amounts, disabled, f => onResumeDataChanged(x => x.copy(amounts = f(x.amounts)))),
        validationError = resumeFormData.firstValidationError,
        submitLabel = "Resume",
        canSubmit = data.isValid,
        onFormSubmit = submitEditor(data.submitRequest, props.ctx.api.client.resumeSubscription(subscriptionId, _))
      )
    }

    def extraChargeForm(subscriptionId: UUID): ReactElement = {
      val data = extraChargeFormData.data
      val disabled = editControl.inputDisabled
      inlineEditor(
        inputs = Seq(
          fieldBox(key = "label", boxMinWidth = "200px")(
            SubscriptionExtraChargeLabelInput.component(
              SubscriptionExtraChargeLabelInput.Props(
                field = data.label,
                disabled = disabled,
                onChange = value => onExtraChargeDataChanged(x => x.copy(label = x.label.updated(value)))
              )
            )
          ),
          fieldBox(key = "amount", boxMinWidth = "140px")(
            RequiredSubscriptionAmountInput.component(
              RequiredSubscriptionAmountInput.Props(
                field = data.amount,
                disabled = disabled,
                onChange = value => onExtraChargeDataChanged(x => x.copy(amount = x.amount.updated(value)))
              )
            )
          ),
          selectField(
            key = "recurrence",
            label = "Recurrence",
            value = data.recurrence.entryName,
            disabled = disabled,
            options = SubscriptionExtraChargeRecurrence.values.toSeq.map(r =>
              r.entryName -> SubscriptionStatusLogic.extraChargeRecurrenceLabel(r)
            ),
            onChange =
              v => onExtraChargeDataChanged(_.copy(recurrence = SubscriptionExtraChargeRecurrence.fromEntryName(v)))
          ),
          dateField(
            key = "due-date",
            field = data.dueDate,
            disabled = disabled,
            onChange = value => onExtraChargeDataChanged(x => x.copy(dueDate = x.dueDate.updated(value)))
          )
        ),
        validationError = extraChargeFormData.firstValidationError,
        submitLabel = "Add extra charge",
        canSubmit = data.isValid,
        onFormSubmit =
          submitEditor(data.submitRequest, props.ctx.api.client.addSubscriptionExtraCharge(subscriptionId, _))
      )
    }

    def details(s: GetSubscriptions.Response.Subscription, term: Option[SubscriptionTerm]): String =
      List(
        Some(
          SubscriptionStatusLogic.cadenceLabel(
            s.cadence,
            intervalDays = s.intervalDays,
            intervalMonths = s.intervalMonths
          )
        ),
        term
          .map(t => SubscriptionStatusLogic.amountLabel(amountMin = t.amountMin, amountMax = t.amountMax))
          .filter(_.nonEmpty),
        Some(SubscriptionStatusLogic.paymentMethodLabel(s.paymentMethod)),
        s.merchantId.flatMap(merchantNames.get),
        s.categoryId.flatMap(categoryNames.get),
        s.description
      ).flatten.mkString(" · ")

    // Terms are half-open, so a closed term's end is the first date it no longer applies.
    def termHistory(s: GetSubscriptions.Response.Subscription): ReactElement = {
      val current = SubscriptionStatusLogic.coveringTerm(s.terms, today)
      val lines: List[ReactElement] = s.terms.sortBy(_.effectiveFrom.toEpochDay).map { t =>
        val range = t.effectiveTo match {
          case Some(to) => s"${t.effectiveFrom} until $to"
          case None => s"${t.effectiveFrom} onwards"
        }
        val amount = SubscriptionStatusLogic.amountLabel(amountMin = t.amountMin, amountMax = t.amountMax) match {
          case "" => "Unknown amount"
          case label => label
        }
        val marker = if (current.contains(t)) " (current)" else ""
        mui
          .Typography(s"$range · $amount$marker")
          .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" })
          .withKey(t.id.toString)
      }
      mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; marginTop = "4px" })(lines*)
    }

    // Extras are listed for reference only: they do not affect the status or the expected amount.
    def extraCharges(s: GetSubscriptions.Response.Subscription): ReactElement = {
      val lines: List[ReactElement] = s.extraCharges.map { c =>
        mui.Box
          .sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "6px" })(
            mui
              .Typography(SubscriptionStatusLogic.extraChargeLabel(c))
              .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" }),
            mui.Button
              .normal()("Remove")
              .size("small")
              .disabled(deleteControl.isSubmitting)
              .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "11px"; minWidth = "0" })
              .onClick(_ => handleDeleteExtraCharge(subscriptionId = s.id, extraChargeId = c.id))
          )
          .withKey(c.id.toString)
      }
      mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; marginTop = "4px" })(lines*)
    }

    def actionButton(key: String, label: String, onClick: () => Unit): ReactElement =
      mui.Button
        .normal()(label)
        .size("small")
        .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)" })
        .onClick(_ => onClick())
        .withKey(key)

    def summary(s: GetSubscriptions.Response.Subscription): ReactElement = {
      val term = SubscriptionStatusLogic.expectedTerm(s.terms, today)
      val termActions: Seq[ReactElement] =
        if (s.terms.exists(_.effectiveTo.isEmpty))
          Seq(
            actionButton(key = "change-price", label = "Change price", onClick = () => startPriceChange(s.id)),
            actionButton(key = "cancel", label = "Cancel", onClick = () => startCancel(s.id))
          )
        else Seq(actionButton(key = "resume", label = "Resume", onClick = () => startResume(s.id)))
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
              .status(
                terms = s.terms,
                cadence = s.cadence,
                intervalDays = s.intervalDays,
                intervalMonths = s.intervalMonths,
                today = today
              )
              .toOption
              .fold[ReactElement](Fragment())(statusChip)
          ),
          mui
            .Typography(details(s, term))
            .sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" }),
          termHistory(s),
          extraCharges(s)
        ),
        mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "4px"; alignSelf = "flex-start" })(
          actionButton(key = "edit", label = "Edit", onClick = () => startEditing(s)),
          Fragment(termActions*),
          actionButton(key = "add-extra-charge", label = "Add extra charge", onClick = () => startAddExtraCharge(s.id)),
          mui.Button
            .normal()("Delete")
            .size("small")
            .disabled(deleteControl.isSubmitting)
            .sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
            .onClick(_ => handleDelete(s.id))
        )
      )
    }

    val items: Seq[ReactElement] = props.subscriptions.data.map { s =>
      def withEditorFor(id: UUID)(editorForm: => ReactElement): ReactElement =
        if (id == s.id) Fragment(summary(s), editorForm) else summary(s)
      val content: ReactElement = editor match {
        case SubscriptionEditor.Closed => summary(s)
        case SubscriptionEditor.Edit(id) => if (id == s.id) editForm(s.id) else summary(s)
        case SubscriptionEditor.PriceChange(id) => withEditorFor(id)(priceChangeForm(s.id))
        case SubscriptionEditor.Cancel(id) => withEditorFor(id)(cancelForm(s.id))
        case SubscriptionEditor.Resume(id) => withEditorFor(id)(resumeForm(s.id))
        case SubscriptionEditor.AddExtraCharge(id) => withEditorFor(id)(extraChargeForm(s.id))
      }
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
