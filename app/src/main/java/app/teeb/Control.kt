package app.teeb

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent

object Control {
    /** Returns a spoken reply, or null when the text is not a phone-control command. */
    fun run(ctx: Context, raw: String): String? {
        val s = raw.lowercase().trim().trimEnd('.', '!', '?')
        Regex("^open (.+)").find(s)?.let { return open(ctx, it.groupValues[1].trim()) }
        val ctl = Regex("^(go home|go back|recents|lock screen|screenshot|scroll (up|down)|(click|tap|press) .+|type .+|read screen|what'?s on (the |my )?screen)$")
        if (!ctl.matches(s)) return null
        val a = ControlService.inst ?: return "Please switch TEEB on in Accessibility settings first."
        Regex("^(?:click|tap|press) (.+)").find(s)?.let { return if (a.click(it.groupValues[1])) "Done." else "I can't see ${it.groupValues[1]} on the screen." }
        Regex("^type (.+)").find(s)?.let { return if (a.type(it.groupValues[1])) "Typed." else "Tap a text box first." }
        return when {
            s == "go home" -> { a.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); "Home." }
            s == "go back" -> { a.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); "Back." }
            s == "recents" -> { a.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); "Here are your recent apps." }
            s == "lock screen" -> { a.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN); "Locking." }
            s == "screenshot" -> { a.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT); "Screenshot taken." }
            s == "scroll down" -> if (a.scroll(true)) "Scrolled." else "Nothing to scroll."
            s == "scroll up" -> if (a.scroll(false)) "Scrolled." else "Nothing to scroll."
            else -> a.read()
        }
    }

    private fun open(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val app = pm.getInstalledApplications(0).firstOrNull {
            pm.getLaunchIntentForPackage(it.packageName) != null && pm.getApplicationLabel(it).toString().lowercase().contains(name)
        } ?: return "I can't find an app called $name."
        val i = pm.getLaunchIntentForPackage(app.packageName)!!
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = ControlService.inst ?: ctx
        c.startActivity(i)
        return "Opening ${pm.getApplicationLabel(app)}."
    }
}
