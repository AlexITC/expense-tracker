package net.wiringbits.components.widgets

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.core.ReactiveHooks
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.compat.RouterCompat
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useHistory
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}

object AuthenticatedLayout {
  def apply(ctx: AppContext, user: User, content: ReactElement): KeyAddingStage =
    component(Props(ctx, user, content))

  case class Props(ctx: AppContext, user: User, content: ReactElement)

  private case class NavItem(path: String, label: String)
  private case class NavGroup(title: String, items: List[NavItem])

  private val navGroups = List(
    NavGroup(
      "Process",
      List(
        NavItem("/receipts", "Receipts"),
        NavItem("/bank-statements", "Bank statements")
      )
    ),
    NavGroup(
      "Ledger",
      List(
        NavItem("/expenses", "Expenses"),
        NavItem("/refunds", "Refunds")
      )
    ),
    NavGroup(
      "Organize",
      List(
        NavItem("/merchants", "Merchants"),
        NavItem("/merchant-review", "Review"),
        NavItem("/import-rules", "Import rules"),
        NavItem("/categories", "Categories")
      )
    ),
    NavGroup("Goals", List(NavItem("/savings", "Savings"))),
    NavGroup("Analyse", List(NavItem("/reports", "Reports"), NavItem("/charts", "Charts"))),
    NavGroup("Account", List(NavItem("/me", "Account")))
  )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val history = useHistory()
    val pathname = RouterCompat.useTypedLocation().pathname
    val isDark = ReactiveHooks.useDistinctValue(props.ctx.$darkMode)

    val (showUserMenu, setShowUserMenu) = Hooks.useState(false)
    val (showSignOutConfirm, setShowSignOutConfirm) = Hooks.useState(false)

    val userInitial = props.user.name.string.headOption.map(_.toUpper.toString).getOrElse("?")

    val drawerContent: ReactElement = Fragment(
      // Logo bar
      mui.Box.sx(new CSSPropertiesUtils {
        padding = "16px"
        display = "flex"
        alignItems = "center"
        gap = "10px"
      })(
        mui.Box.sx(new CSSPropertiesUtils {
          width = "30px"
          height = "30px"
          borderRadius = "8px"
          backgroundColor = "var(--lc-brand)"
        })(
          mui
            .Typography("L")
            .sx(new CSSPropertiesUtils {
              color = "var(--lc-brand-on)"
              fontWeight = "700"
              fontSize = "14px"
              textAlign = "center"
              lineHeight = "30px"
            })
        ),
        mui
          .Typography("Ledger")
          .sx(new CSSPropertiesUtils {
            fontWeight = "700"
            fontSize = "17px"
            letterSpacing = "-0.02em"
            color = "var(--lc-text)"
            fontFamily = "\"IBM Plex Sans\", sans-serif"
          })
      ),
      mui.Divider(),
      // Nav groups
      mui.Box.sx(new CSSPropertiesUtils {
        flex = "1"
        padding = "8px 0"
        overflowY = "auto"
        backgroundColor = "var(--lc-paper)"
      })(
        Fragment(
          navGroups.flatMap { grp =>
            List[ReactElement](
              mui
                .Typography(grp.title)
                .sx(new CSSPropertiesUtils {
                  padding = "4px 16px 0"
                  lineHeight = "30px"
                  fontSize = "11px"
                  fontWeight = "700"
                  letterSpacing = "0.08em"
                  textTransform = "uppercase"
                  color = "var(--lc-text-2)"
                  display = "block"
                  fontFamily = "\"IBM Plex Sans\", sans-serif"
                })
                .withKey(s"hdr-${grp.title}")
                .build
            ) ++ grp.items.map { item =>
              val isActive = pathname == item.path || pathname.startsWith(item.path + "/")
              val bgColor = if (isActive) "var(--lc-brand)" else "transparent"
              val textColor = if (isActive) "var(--lc-brand-on)" else "var(--lc-text)"

              mui.ListItemButton
                .normal()(
                  mui
                    .Typography(item.label)
                    .sx(new CSSPropertiesUtils {
                      fontSize = "14px"
                      fontWeight = "600"
                      color = textColor
                      fontFamily = "\"IBM Plex Sans\", sans-serif"
                    })
                )
                .sx(new CSSPropertiesUtils {
                  margin = "0 8px 2px"
                  borderRadius = "8px"
                  backgroundColor = bgColor
                })
                .onClick(e => {
                  if (e.ctrlKey || e.metaKey) dom.window.open(item.path, "_blank")
                  else history.push(item.path)
                })
                .withKey(item.path)
                .build
            }
          }*
        )
      ),
      mui.Divider(),
      // User chip area — relative container for the popup
      mui.Box.sx(new CSSPropertiesUtils { position = "relative" })(
        // User chip button
        mui.ListItemButton
          .normal()(
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "8px" })(
              mui.Box.sx(new CSSPropertiesUtils {
                width = "24px"; height = "24px"; borderRadius = "50%"
                backgroundColor = "var(--lc-brand)"
                display = "flex"; alignItems = "center"; justifyContent = "center"
                flexShrink = "0"
              })(
                mui
                  .Typography(userInitial)
                  .sx(new CSSPropertiesUtils { color = "var(--lc-brand-on)"; fontSize = "11px"; fontWeight = "700" })
              ),
              mui
                .Typography(props.user.name.string)
                .sx(new CSSPropertiesUtils {
                  fontSize = "13px"
                  fontWeight = "600"
                  color = "var(--lc-text)"
                  fontFamily = "\"IBM Plex Sans\", sans-serif"
                  overflow = "hidden"
                  textOverflow = "ellipsis"
                  whiteSpace = "nowrap"
                })
            )
          )
          .sx(new CSSPropertiesUtils { margin = "4px 8px"; borderRadius = "8px"; padding = "6px 8px" })
          .onClick(_ => setShowUserMenu(!showUserMenu)),
        // Dropdown popup
        if (showUserMenu)
          mui.Box.sx(new CSSPropertiesUtils {
            position = "absolute"
            bottom = "48px"
            left = "8px"
            right = "8px"
            backgroundColor = "var(--lc-paper)"
            border = "1px solid var(--lc-border)"
            borderRadius = "8px"
            boxShadow = "0 4px 12px rgba(0,0,0,0.12)"
            zIndex = "1000"
          })(
            mui.ListItemButton
              .normal()(
                mui
                  .Typography(if (isDark) "Light mode" else "Dark mode")
                  .sx(new CSSPropertiesUtils {
                    fontSize = "14px"
                    fontWeight = "600"
                    color = "var(--lc-text)"
                    fontFamily = "\"IBM Plex Sans\", sans-serif"
                  })
              )
              .sx(new CSSPropertiesUtils { borderRadius = "8px"; padding = "8px 12px" })
              .onClick(_ => { setShowUserMenu(false); props.ctx.$darkMode := !isDark }),
            mui.ListItemButton
              .normal()(
                mui
                  .Typography("Sign out")
                  .sx(new CSSPropertiesUtils {
                    fontSize = "14px"
                    fontWeight = "600"
                    color = "var(--lc-error)"
                    fontFamily = "\"IBM Plex Sans\", sans-serif"
                  })
              )
              .sx(new CSSPropertiesUtils { borderRadius = "8px"; padding = "8px 12px" })
              .onClick(_ => { setShowUserMenu(false); setShowSignOutConfirm(true) })
          )
        else Fragment()
      )
    )

    // Sign-out confirmation overlay
    val confirmOverlay: ReactElement =
      if (showSignOutConfirm)
        mui.Box.sx(new CSSPropertiesUtils {
          position = "fixed"
          top = "0"; left = "0"; right = "0"; bottom = "0"
          backgroundColor = "rgba(0,0,0,0.45)"
          display = "flex"
          alignItems = "center"
          justifyContent = "center"
          zIndex = "9999"
        })(
          mui.Box.sx(new CSSPropertiesUtils {
            backgroundColor = "var(--lc-paper)"
            borderRadius = "12px"
            padding = "28px 32px"
            maxWidth = "380px"
            width = "90%"
            boxShadow = "0 8px 32px rgba(0,0,0,0.18)"
          })(
            mui
              .Typography("Sign out?")
              .sx(new CSSPropertiesUtils {
                fontWeight = "700"
                fontSize = "17px"
                color = "var(--lc-text)"
                fontFamily = "\"IBM Plex Sans\", sans-serif"
                marginBottom = "8px"
              }),
            mui
              .Typography("You'll be signed out of Ledger. Any unsaved changes will be lost.")
              .sx(new CSSPropertiesUtils {
                fontSize = "14px"
                color = "var(--lc-text-2)"
                fontFamily = "\"IBM Plex Sans\", sans-serif"
                marginBottom = "20px"
              }),
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "8px"; justifyContent = "flex-end" })(
              mui.Button
                .normal()("Cancel")
                .variant("outlined")
                .sx(new CSSPropertiesUtils { borderRadius = "8px"; fontFamily = "\"IBM Plex Sans\", sans-serif" })
                .onClick(_ => setShowSignOutConfirm(false)),
              mui.Button
                .normal()("Sign out")
                .variant("contained")
                .color("error")
                .sx(new CSSPropertiesUtils { borderRadius = "8px"; fontFamily = "\"IBM Plex Sans\", sans-serif" })
                .onClick(_ => { setShowSignOutConfirm(false); history.push("/signout") })
            )
          )
        )
      else Fragment()

    Fragment(
      mui.Box.sx(new CSSPropertiesUtils {
        display = "flex"
        height = "100vh"
        backgroundColor = "var(--lc-bg)"
      })(
        // Left drawer
        mui.Box.sx(new CSSPropertiesUtils {
          width = "256px"
          flexShrink = "0"
          height = "100vh"
          display = "flex"
          flexDirection = "column"
          borderRight = "1px solid var(--lc-border)"
          backgroundColor = "var(--lc-paper)"
        })(drawerContent),
        // Right: content
        mui.Box.sx(new CSSPropertiesUtils {
          flex = "1"
          minWidth = "0"
          overflowY = "auto"
          height = "100vh"
        })(props.content)
      ),
      confirmOverlay
    )
  }
}
