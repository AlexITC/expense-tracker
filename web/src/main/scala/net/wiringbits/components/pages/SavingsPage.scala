package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.components.widgets.Loader
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.models.savings.*
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import net.wiringbits.webapp.utils.slinkyUtils.components.core.widgets.Container
import net.wiringbits.webapp.utils.slinkyUtils.core.GenericHooks
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, onSubmit}

import java.time.LocalDate
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

object SavingsPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (timesRefreshing, forceRefresh) = GenericHooks.useForceRefresh
    val (timesRefreshingGoals, forceRefreshGoals) = GenericHooks.useForceRefresh
    val (expandedGoalId, setExpandedGoalId) = Hooks.useState[Option[UUID]](None)

    mui.Box.sx(new CSSPropertiesUtils { padding = "24px"; maxWidth = "800px" })(
      AsyncComponent[GetSavingsGoals.Response](
        fetch = () => props.ctx.api.client.getSavingsGoals,
        render = goals =>
          AsyncComponent[GetMerchants.Response](
            fetch = () => props.ctx.api.client.getMerchants,
            render = merchants =>
              SavingsGoalList(
                props.ctx,
                goals,
                merchants,
                () => forceRefresh(),
                () => forceRefreshGoals(),
                expandedGoalId,
                id => setExpandedGoalId(id)
              ),
            progressIndicator = () => Loader(props.ctx),
            watchedObjects = List(timesRefreshing)
          ),
        progressIndicator = () => Loader(props.ctx),
        watchedObjects = List(timesRefreshing, timesRefreshingGoals)
      )
    )
  }
}

object SavingsGoalList {
  def apply(
      ctx: AppContext,
      goals: GetSavingsGoals.Response,
      merchants: GetMerchants.Response,
      onRefresh: () => Unit,
      onGoalsRefresh: () => Unit,
      expandedGoalId: Option[UUID],
      onExpandedChange: Option[UUID] => Unit
  ): KeyAddingStage =
    component(Props(ctx, goals, merchants, onRefresh, onGoalsRefresh, expandedGoalId, onExpandedChange))

  case class Props(
      ctx: AppContext,
      goals: GetSavingsGoals.Response,
      merchants: GetMerchants.Response,
      onRefresh: () => Unit,
      onGoalsRefresh: () => Unit,
      expandedGoalId: Option[UUID],
      onExpandedChange: Option[UUID] => Unit
  )

  private val cardStyle = new CSSPropertiesUtils {
    border = "1px solid var(--lc-border)"
    borderRadius = "8px"
    backgroundColor = "var(--lc-paper)"
    marginBottom = "8px"
    padding = "14px 16px"
    width = "100%"
  }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (newName, setNewName) = Hooks.useState("")
    val (newTarget, setNewTarget) = Hooks.useState("")
    val (error, setError) = Hooks.useState("")
    val (editingGoalId, setEditingGoalId) = Hooks.useState[Option[UUID]](None)
    val (editName, setEditName) = Hooks.useState("")
    val (editTarget, setEditTarget) = Hooks.useState("")

    def handleCreateGoal(): Unit = {
      val targetAmount = if (newTarget.isEmpty) None else Try(BigDecimal(newTarget)).toOption
      if (!newTarget.isEmpty && targetAmount.isEmpty) setError("Invalid target amount")
      else
        props.ctx.api.client
          .createSavingsGoal(CreateSavingsGoal.Request(newName, targetAmount))
          .onComplete {
            case Success(_) =>
              setNewName(""); setNewTarget(""); setError(""); props.onRefresh()
            case Failure(ex) => setError(ex.getMessage)
          }
    }

    def handleUpdateGoal(goalId: UUID): Unit = {
      val targetAmount = if (editTarget.isEmpty) None else Try(BigDecimal(editTarget)).toOption
      if (!editTarget.isEmpty && targetAmount.isEmpty) setError("Invalid target amount")
      else
        props.ctx.api.client
          .updateSavingsGoal(goalId, UpdateSavingsGoal.Request(editName, targetAmount))
          .onComplete {
            case Success(_) =>
              setEditingGoalId(None); setError(""); props.onRefresh()
            case Failure(ex) => setError(ex.getMessage)
          }
    }

    def handleDeleteGoal(goalId: UUID): Unit =
      props.ctx.api.client
        .deleteSavingsGoal(goalId)
        .onComplete {
          case Success(_) =>
            if (props.expandedGoalId.contains(goalId)) props.onExpandedChange(None)
            props.onRefresh()
          case Failure(ex) => setError(ex.getMessage)
        }

    val createForm: ReactElement = form(onSubmit := { e =>
      e.preventDefault()
      handleCreateGoal()
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
        mui.TextField
          .outlined()
          .label("Goal name")
          .value(newName)
          .size("small")
          .onChange(e => setNewName(e.target.asInstanceOf[dom.HTMLInputElement].value)),
        mui.TextField
          .outlined()
          .label("Target amount (optional)")
          .value(newTarget)
          .size("small")
          .onChange(e => setNewTarget(e.target.asInstanceOf[dom.HTMLInputElement].value)),
        mui.Button
          .normal()("Add goal")
          .variant("contained")
          .`type`("submit")
          .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)"; borderRadius = "8px" })
      )
    )

    val goalItems: Seq[ReactElement] = props.goals.data.map { goal =>
      val merchantMap = props.merchants.data.map(m => m.id -> m.name).toMap
      val isEditing = editingGoalId.contains(goal.id)
      val isExpanded = props.expandedGoalId.contains(goal.id)

      val balanceText = s"Balance: ${goal.balance}"
      val targetText = goal.targetAmount.map(t => s" / Target: $t").getOrElse("")
      val progressText = goal.progress.map(p => f" (${p * 100}%.1f%%)").getOrElse("")

      val goalContent: ReactElement =
        if (isEditing)
          form(onSubmit := { e =>
            e.preventDefault()
            handleUpdateGoal(goal.id)
          })(
            mui.Box.sx(new CSSPropertiesUtils {
              display = "flex"; flexWrap = "wrap"; alignItems = "center"; gap = "12px"
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
                .label("Target (optional)")
                .value(editTarget)
                .size("small")
                .onChange(e => setEditTarget(e.target.asInstanceOf[dom.HTMLInputElement].value)),
              (mui.Button
                .normal()("Save")
                .size("small")
                .variant("contained")
                .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
                .`type`("submit")
                .build: ReactElement),
              mui.Button
                .normal()("Cancel")
                .size("small")
                .onClick(_ => setEditingGoalId(None))
            )
          )
        else
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"
            alignItems = "center"
            justifyContent = "space-between"
            width = "100%"
          })(
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "2px" })(
              mui
                .Typography(goal.name)
                .sx(new CSSPropertiesUtils {
                  fontSize = "15px"
                  fontWeight = "600"
                  color = "var(--lc-text)"
                  fontFamily = "\"IBM Plex Sans\", sans-serif"
                }),
              mui
                .Typography(balanceText + targetText + progressText)
                .sx(new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-brand)" })
            ),
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "4px" })(
              mui.Button
                .normal()("Allocations")
                .size("small")
                .variant(if (isExpanded) "contained" else "outlined")
                .sx(if (isExpanded) new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" }
                else new CSSPropertiesUtils {})
                .onClick { _ =>
                  if (isExpanded) props.onExpandedChange(None)
                  else props.onExpandedChange(Some(goal.id))
                },
              mui.Button
                .normal()("Edit")
                .size("small")
                .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)" })
                .onClick { _ =>
                  setEditingGoalId(Some(goal.id))
                  setEditName(goal.name)
                  setEditTarget(goal.targetAmount.map(_.toString).getOrElse(""))
                },
              mui.Button
                .normal()("Delete")
                .size("small")
                .sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
                .onClick(_ => handleDeleteGoal(goal.id))
            )
          )

      Fragment(
        mui.Box.sx(cardStyle)(goalContent).withKey(goal.id.toString),
        if (isExpanded)
          mui.Box
            .sx(new CSSPropertiesUtils {
              marginLeft = "16px"
              marginBottom = "8px"
              border = "1px solid var(--lc-border)"
              borderRadius = "8px"
              backgroundColor = "var(--lc-bg)"
              padding = "12px 16px"
            })(
              SavingsAllocationSection(props.ctx, goal.id, merchantMap, props.onGoalsRefresh): ReactElement
            )
            .withKey(s"${goal.id}-alloc")
        else
          Fragment().withKey(s"${goal.id}-empty")
      ).withKey(goal.id.toString)
    }

    Fragment(
      createForm,
      if (error.nonEmpty)
        mui
          .Typography(error)
          .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "13px"; marginBottom = "8px" })
      else Fragment(),
      Fragment(goalItems*)
    )
  }
}

object SavingsAllocationSection {
  def apply(
      ctx: AppContext,
      goalId: UUID,
      merchantMap: Map[UUID, String],
      onGoalsRefresh: () => Unit
  ): KeyAddingStage =
    component(Props(ctx, goalId, merchantMap, onGoalsRefresh))

  case class Props(ctx: AppContext, goalId: UUID, merchantMap: Map[UUID, String], onGoalsRefresh: () => Unit)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (timesRefreshing, forceRefresh) = GenericHooks.useForceRefresh

    AsyncComponent[GetSavingsAllocations.Response](
      fetch = () => props.ctx.api.client.getSavingsAllocations(props.goalId),
      render = allocations =>
        SavingsAllocationList(
          props.ctx,
          props.goalId,
          allocations,
          props.merchantMap,
          () => forceRefresh(),
          props.onGoalsRefresh
        ),
      progressIndicator = () => Loader(props.ctx),
      watchedObjects = List(timesRefreshing)
    )
  }
}

object SavingsAllocationList {
  def apply(
      ctx: AppContext,
      goalId: UUID,
      allocations: GetSavingsAllocations.Response,
      merchantMap: Map[UUID, String],
      onRefresh: () => Unit,
      onGoalsRefresh: () => Unit
  ): KeyAddingStage =
    component(Props(ctx, goalId, allocations, merchantMap, onRefresh, onGoalsRefresh))

  case class Props(
      ctx: AppContext,
      goalId: UUID,
      allocations: GetSavingsAllocations.Response,
      merchantMap: Map[UUID, String],
      onRefresh: () => Unit,
      onGoalsRefresh: () => Unit
  )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (newAmount, setNewAmount) = Hooks.useState("")
    val (newDate, setNewDate) = Hooks.useState(LocalDate.now().toString)
    val (newNote, setNewNote) = Hooks.useState("")
    val (newMerchantId, setNewMerchantId) = Hooks.useState("")
    val (estimate, setEstimate) = Hooks.useState("")
    val (error, setError) = Hooks.useState("")
    val (editingId, setEditingId) = Hooks.useState[Option[UUID]](None)
    val (editAmount, setEditAmount) = Hooks.useState("")
    val (editDate, setEditDate) = Hooks.useState("")
    val (editNote, setEditNote) = Hooks.useState("")
    val (editMerchantId, setEditMerchantId) = Hooks.useState("")

    def fetchEstimate(merchantIdStr: String): Unit =
      Try(UUID.fromString(merchantIdStr)).toOption match {
        case None => setEstimate("")
        case Some(mid) =>
          props.ctx.api.client
            .getSavingsEstimate(mid)
            .onComplete {
              case Success(resp) => setEstimate(resp.typicalAmount.map(a => s"Typical: $a").getOrElse(""))
              case Failure(_) => setEstimate("")
            }
      }

    def resetAddForm(): Unit = {
      setNewAmount(""); setNewDate(LocalDate.now().toString); setNewNote("")
      setNewMerchantId(""); setEstimate(""); setError("")
    }

    def handleCreate(): Unit =
      (Try(BigDecimal(newAmount)).toOption, Try(LocalDate.parse(newDate)).toOption) match {
        case (None, _) => setError("Invalid amount")
        case (_, None) => setError("Invalid date (use YYYY-MM-DD)")
        case (Some(amount), Some(date)) =>
          val merchantId = if (newMerchantId.isEmpty) None else Try(UUID.fromString(newMerchantId)).toOption
          val note = Option(newNote.trim).filter(_.nonEmpty)
          props.ctx.api.client
            .createSavingsAllocation(props.goalId, CreateSavingsAllocation.Request(amount, date, note, merchantId))
            .onComplete {
              case Success(_) =>
                resetAddForm(); props.onRefresh(); props.onGoalsRefresh()
              case Failure(ex) => setError(ex.getMessage)
            }
      }

    def handleUpdate(allocationId: UUID): Unit =
      (Try(BigDecimal(editAmount)).toOption, Try(LocalDate.parse(editDate)).toOption) match {
        case (None, _) => setError("Invalid amount")
        case (_, None) => setError("Invalid date (use YYYY-MM-DD)")
        case (Some(amount), Some(date)) =>
          val merchantId = if (editMerchantId.isEmpty) None else Try(UUID.fromString(editMerchantId)).toOption
          val note = Option(editNote.trim).filter(_.nonEmpty)
          props.ctx.api.client
            .updateSavingsAllocation(
              props.goalId,
              allocationId,
              UpdateSavingsAllocation.Request(amount, date, note, merchantId)
            )
            .onComplete {
              case Success(_) => setEditingId(None); setError(""); props.onRefresh(); props.onGoalsRefresh()
              case Failure(ex) => setError(ex.getMessage)
            }
      }

    def handleDelete(allocationId: UUID): Unit =
      props.ctx.api.client
        .deleteSavingsAllocation(props.goalId, allocationId)
        .onComplete {
          case Success(_) => props.onRefresh(); props.onGoalsRefresh()
          case Failure(ex) => setError(ex.getMessage)
        }

    def merchantSelectField(selectedId: String, setId: String => Unit, label: String): ReactElement = {
      val none = mui.MenuItem.normal()("(none)").value("").withKey("none").build
      val items = none +: props.merchantMap.toList.sortBy(_._2).map { case (id, name) =>
        mui.MenuItem.normal()(name).value(id.toString).withKey(id.toString).build
      }
      mui.TextField
        .outlined()
        .select(true)
        .label(label)
        .value(selectedId)
        .size("small")
        .onChange(e => setId(e.target.asInstanceOf[dom.HTMLInputElement].value))
        .sx(new CSSPropertiesUtils { minWidth = "140px" })(items*)
    }

    val createForm: ReactElement = form(onSubmit := { e =>
      e.preventDefault()
      handleCreate()
    })(
      mui.Box.sx(new CSSPropertiesUtils {
        display = "flex"
        flexWrap = "wrap"
        alignItems = "center"
        gap = "10px"
        marginBottom = "12px"
      })(
        mui.TextField
          .outlined()
          .label("Amount (+ or -)")
          .value(newAmount)
          .size("small")
          .onChange(e => setNewAmount(e.target.asInstanceOf[dom.HTMLInputElement].value)),
        mui.TextField
          .outlined()
          .label("Date")
          .value(newDate)
          .size("small")
          .onChange(e => setNewDate(e.target.asInstanceOf[dom.HTMLInputElement].value)),
        mui.TextField
          .outlined()
          .label("Note (optional)")
          .value(newNote)
          .size("small")
          .onChange(e => setNewNote(e.target.asInstanceOf[dom.HTMLInputElement].value)),
        merchantSelectField(newMerchantId, v => { setNewMerchantId(v); fetchEstimate(v) }, "Merchant (optional)"),
        if (estimate.nonEmpty)
          mui.Typography(estimate).sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" })
        else Fragment(),
        mui.Button
          .normal()("Add")
          .variant("contained")
          .size("small")
          .`type`("submit")
          .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
      )
    )

    val allocationItems: Seq[ReactElement] = props.allocations.data.map { alloc =>
      val isEditing = editingId.contains(alloc.id)
      val merchantName = alloc.merchantId.flatMap(props.merchantMap.get).getOrElse("")
      val meta = List(alloc.date.toString, merchantName, alloc.note.getOrElse("")).filter(_.nonEmpty).mkString(" · ")

      val content: ReactElement =
        if (isEditing)
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"; flexWrap = "wrap"; alignItems = "center"; gap = "10px"
          })(
            mui.TextField
              .outlined()
              .autoFocus(true)
              .label("Amount")
              .value(editAmount)
              .size("small")
              .onChange(e => setEditAmount(e.target.asInstanceOf[dom.HTMLInputElement].value)),
            mui.TextField
              .outlined()
              .label("Date")
              .value(editDate)
              .size("small")
              .onChange(e => setEditDate(e.target.asInstanceOf[dom.HTMLInputElement].value)),
            mui.TextField
              .outlined()
              .label("Note")
              .value(editNote)
              .size("small")
              .onChange(e => setEditNote(e.target.asInstanceOf[dom.HTMLInputElement].value)),
            merchantSelectField(editMerchantId, setEditMerchantId, "Merchant"),
            mui.Button
              .normal()("Save")
              .size("small")
              .variant("contained")
              .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
              .onClick(_ => handleUpdate(alloc.id)),
            mui.Button.normal()("Cancel").size("small").onClick(_ => setEditingId(None))
          )
        else
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"
            alignItems = "center"
            justifyContent = "space-between"
            width = "100%"
          })(
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "2px" })(
              mui
                .Typography(alloc.amount.toString)
                .sx(new CSSPropertiesUtils {
                  fontFamily = "\"IBM Plex Mono\", monospace"
                  fontSize = "14px"
                  fontWeight = "600"
                  color = if (alloc.amount >= 0) "var(--lc-brand)" else "var(--lc-error)"
                }),
              if (meta.nonEmpty)
                mui.Typography(meta).sx(new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" })
              else Fragment()
            ),
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "4px" })(
              mui.Button
                .normal()("Edit")
                .size("small")
                .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)" })
                .onClick { _ =>
                  setEditingId(Some(alloc.id))
                  setEditAmount(alloc.amount.toString)
                  setEditDate(alloc.date.toString)
                  setEditNote(alloc.note.getOrElse(""))
                  setEditMerchantId(alloc.merchantId.map(_.toString).getOrElse(""))
                },
              mui.Button
                .normal()("Delete")
                .size("small")
                .sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
                .onClick(_ => handleDelete(alloc.id))
            )
          )

      mui.Box
        .sx(new CSSPropertiesUtils {
          border = "1px solid var(--lc-border)"
          borderRadius = "6px"
          backgroundColor = "var(--lc-paper)"
          marginBottom = "4px"
          padding = "8px 12px"
          width = "100%"
        })(content)
        .withKey(alloc.id.toString)
    }

    Fragment(
      mui
        .Typography("Allocations")
        .sx(new CSSPropertiesUtils {
          fontSize = "12px"; color = "var(--lc-text-2)"; fontWeight = "600"; marginBottom = "8px"
        }),
      createForm,
      if (error.nonEmpty)
        mui
          .Typography(error)
          .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "12px"; marginBottom = "8px" })
      else Fragment(),
      Fragment(allocationItems*)
    )
  }
}
