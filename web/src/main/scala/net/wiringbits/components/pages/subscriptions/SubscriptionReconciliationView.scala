package net.wiringbits.components.pages.subscriptions

import com.olvind.mui.muiIconsMaterial.components as muiIcons
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.api.models.subscriptions.{GetSubscriptionReconciliation, SubscriptionCycle}
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.core.facade.{Fragment, ReactElement}

import java.util.UUID

/** The reconciled cycles and logged manual payments of one subscription. */
object SubscriptionReconciliationView {
  def apply(
      reconciliation: GetSubscriptionReconciliation.Response.Subscription,
      removeDisabled: Boolean,
      onRemoveManualPayment: UUID => Unit
  ): KeyAddingStage =
    component(Props(reconciliation, removeDisabled, onRemoveManualPayment))

  case class Props(
      reconciliation: GetSubscriptionReconciliation.Response.Subscription,
      removeDisabled: Boolean,
      onRemoveManualPayment: UUID => Unit
  )

  private def lineStyle = new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" }

  private def columnStyle = new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "2px" }

  private def rowStyle = new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "6px" }

  private def toneChip(tone: CycleTone, label: String): ReactElement = {
    val (bg, fg) = tone match {
      case CycleTone.Error => ("var(--lc-badge-error-bg)", "var(--lc-error)")
      case CycleTone.Warning => ("var(--lc-warm-bg)", "var(--lc-warning-text)")
      case CycleTone.Upcoming => ("var(--lc-tint)", "var(--lc-brand-dark)")
      case CycleTone.Settled => ("var(--lc-border)", "var(--lc-text-2)")
    }
    mui.Chip
      .label(label)
      .size("small")
      .sx(new CSSPropertiesUtils {
        height = "18px"; fontSize = "11px"; backgroundColor = bg; color = fg
      })
  }

  private def cycleLine(cycle: SubscriptionCycle): ReactElement =
    mui.Box
      .sx(rowStyle)(
        toneChip(
          SubscriptionReconciliationLogic.tone(cycle.status),
          SubscriptionReconciliationLogic.statusLabel(cycle.status)
        ),
        mui.Typography(SubscriptionReconciliationLogic.cycleDescription(cycle)).sx(lineStyle)
      )
      .withKey(s"${cycle.termId}-${cycle.dueDate}")

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val split = SubscriptionReconciliationLogic.split(props.reconciliation.cycles)

    val onTime: ReactElement =
      if (split.onTime.isEmpty) Fragment()
      else
        mui
          .Accordion(
            mui.AccordionSummary
              .normal()
              .expandIcon(muiIcons.ExpandMore())
              .sx(new CSSPropertiesUtils { minHeight = "0"; padding = "0" })(
                mui.Typography(SubscriptionReconciliationLogic.onTimeSummary(split.onTime.size)).sx(lineStyle)
              ),
            mui.AccordionDetails
              .sx(new CSSPropertiesUtils { padding = "0 0 4px 0" })(
                mui.Box.sx(columnStyle)(split.onTime.map(cycleLine)*)
              )
          )
          .disableGutters(true)
          .elevation(0)
          .sx(new CSSPropertiesUtils { backgroundColor = "transparent" })

    val manualPayments: List[ReactElement] = props.reconciliation.manualPayments.map { p =>
      mui.Box
        .sx(rowStyle)(
          mui.Typography(SubscriptionReconciliationLogic.manualPaymentLabel(p)).sx(lineStyle),
          mui.Button
            .normal()("Remove")
            .size("small")
            .disabled(props.removeDisabled)
            .sx(new CSSPropertiesUtils { color = "var(--lc-error)"; fontSize = "11px"; minWidth = "0" })
            .onClick(_ => props.onRemoveManualPayment(p.id))
        )
        .withKey(p.id.toString)
    }

    mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "2px"; marginTop = "4px" })(
      mui.Box.sx(columnStyle)(split.attention.map(cycleLine)*),
      onTime,
      if (manualPayments.isEmpty) Fragment()
      else
        mui.Box.sx(columnStyle)(
          (mui
            .Typography("Manual payments")
            .sx(new CSSPropertiesUtils {
              fontSize = "12px"; fontWeight = "600"; color = "var(--lc-text-2)"
            })
            .withKey("manual-payments-header"): ReactElement) +: manualPayments*
        )
    )
  }
}
