package e2ee.lite

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The plugin's whole settings screen: a small "Session" menu with two rows, like the Aegires app.
 *   - Set encryption key       opens a dialog with a password box (Save / Clear key / Cancel)
 *   - Encryption preset: <x>   opens a radio list of the presets
 * The JS side keeps the real settings and derives the key; this class only draws and reports:
 *
 *   JS -> here   settingsopen(stateJson)   opens the screen: {"hasKey":bool,"preset":index,"presets":[{"index","label","max"}]}
 *                settingsupdate(partial)   changes values while it is open ("hasKey", "busy", "status", "preset")
 *   here -> JS   settingstake()            {"open":bool,"events":[...]} - JS polls this
 *
 * Events: {"t":"setKey","pw":<password>}  {"t":"clearKey"}  {"t":"setPreset","i":<preset index>}
 */
internal object SettingsScreen {
    private const val PANEL = 0xFF2B2D31.toInt()
    private const val TEXT = 0xFFF2F3F5.toInt()
    private const val MUTED = 0xFF949BA4.toInt()
    private const val DIVIDER = 0xFF3F4147.toInt()

    private val events = ConcurrentLinkedQueue<String>()
    private var dialog: Dialog? = null
    private var child: AlertDialog? = null
    private var activity: Activity? = null
    private var state = JSONObject()

    @Volatile
    private var open = false

    private var keyRow: View? = null
    private var keySub: TextView? = null
    private var presetSub: TextView? = null
    private var statusView: TextView? = null

    /** JS polls this. The open flag lets it stop polling when the screen is closed. */
    fun take(): String {
        val arr = JSONArray()
        while (true) {
            val e = events.poll() ?: break
            arr.put(JSONObject(e))
        }
        return JSONObject().put("open", open).put("events", arr).toString()
    }

    fun show(a: Activity, stateJson: String) {
        a.runOnUiThread {
            try {
                state = JSONObject(stateJson)
                closeViews()
                events.clear()
                val d = Dialog(a)
                d.requestWindowFeature(Window.FEATURE_NO_TITLE)
                d.setContentView(build(a))
                d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                d.setOnDismissListener {
                    if (dialog === d) {
                        child?.dismiss()
                        child = null
                        dialog = null
                        activity = null
                        open = false
                    }
                }
                dialog = d
                activity = a
                open = true
                d.show()
                val w = (a.resources.displayMetrics.widthPixels * 0.9f).toInt()
                d.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            } catch (t: Throwable) {
                open = false
                Toast.makeText(a, "Can't open the settings screen", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Merges changed values into the state and refreshes the rows that show them. */
    fun update(partialJson: String) {
        val a = activity ?: return
        a.runOnUiThread {
            try {
                val p = JSONObject(partialJson)
                val keys = p.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    state.put(k, p.get(k))
                }
                refresh()
            } catch (_: Throwable) {
            }
        }
    }

    fun close() {
        closeViews()
        open = false
    }

    private fun closeViews() {
        try {
            child?.dismiss()
        } catch (_: Throwable) {
        }
        try {
            dialog?.dismiss()
        } catch (_: Throwable) {
        }
        child = null
        dialog = null
        activity = null
    }

    // ---- the menu ------------------------------------------------------------------------------

    private fun build(a: Activity): View {
        val panel = LinearLayout(a)
        panel.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.cornerRadius = dp(a, 24f).toFloat()
        bg.setColor(PANEL)
        panel.background = bg
        panel.setPadding(0, dp(a, 20f), 0, dp(a, 8f))

        val title = TextView(a)
        title.text = "Session"
        title.setTextColor(TEXT)
        title.textSize = 20f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setPadding(dp(a, 24f), 0, dp(a, 24f), dp(a, 12f))
        panel.addView(title)

        val key = row(a, "Set encryption key") { openKeyDialog(a) }
        keyRow = key.first
        keySub = key.second
        panel.addView(key.first)

        val preset = row(a, "Encryption preset") { openPresetDialog(a) }
        presetSub = preset.second
        panel.addView(preset.first)

        val status = TextView(a)
        status.setTextColor(MUTED)
        status.textSize = 13f
        status.setPadding(dp(a, 24f), dp(a, 6f), dp(a, 24f), dp(a, 8f))
        status.visibility = View.GONE
        statusView = status
        panel.addView(status)

        refresh()
        return panel
    }

    /** One menu row: a title and a small line under it. Returns the row and its small line. */
    private fun row(a: Activity, text: String, onTap: () -> Unit): Pair<View, TextView> {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(a, 24f), dp(a, 14f), dp(a, 24f), dp(a, 14f))
        val t = TextView(a)
        t.text = text
        t.setTextColor(TEXT)
        t.textSize = 16f
        box.addView(t)
        val s = TextView(a)
        s.setTextColor(MUTED)
        s.textSize = 13f
        box.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(a, 2f) })
        val ripple = android.util.TypedValue()
        if (a.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) box.setBackgroundResource(ripple.resourceId)
        box.isClickable = true
        box.setOnClickListener { onTap() }
        return Pair(box, s)
    }

    private fun presetLabel(): String {
        val list = state.optJSONArray("presets") ?: return ""
        val want = state.optInt("preset", -1)
        for (i in 0 until list.length()) {
            val p = list.getJSONObject(i)
            if (p.optInt("index") == want) return p.optString("label")
        }
        return ""
    }

    private fun refresh() {
        val busy = state.optBoolean("busy", false)
        keySub?.text = if (state.optBoolean("hasKey", false)) "Key is set" else "No key set"
        keyRow?.alpha = if (busy) 0.5f else 1f
        keyRow?.isEnabled = !busy
        presetSub?.text = presetLabel()
        val status = state.optString("status", "")
        statusView?.text = status
        statusView?.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---- the dialogs ---------------------------------------------------------------------------

    private fun alert(a: Activity): AlertDialog.Builder = AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)

    private fun show(d: AlertDialog) {
        child?.dismiss()
        child = d
        d.setOnDismissListener { if (child === d) child = null }
        d.show()
    }

    private fun openKeyDialog(a: Activity) {
        if (state.optBoolean("busy", false)) return
        val input = EditText(a)
        input.hint = "Encryption key"
        input.setSingleLine(true)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val box = FrameLayout(a)
        box.setPadding(dp(a, 20f), dp(a, 8f), dp(a, 20f), 0)
        box.addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val b = alert(a).setTitle("Set encryption key").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val pw = input.text.toString()
                if (pw.isNotEmpty()) events.add(JSONObject().put("t", "setKey").put("pw", pw).toString())
            }
            .setNegativeButton("Cancel", null)
        if (state.optBoolean("hasKey", false)) {
            b.setNeutralButton("Clear key") { _, _ -> events.add(JSONObject().put("t", "clearKey").toString()) }
        }
        val d = b.create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        show(d)
        input.requestFocus()
    }

    private fun openPresetDialog(a: Activity) {
        val list = state.optJSONArray("presets") ?: return
        val labels = Array(list.length()) { list.getJSONObject(it).optString("label") }
        var checked = 0
        for (i in 0 until list.length()) if (list.getJSONObject(i).optInt("index") == state.optInt("preset", -1)) checked = i
        val d = alert(a).setTitle("Encryption preset")
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                val idx = list.getJSONObject(which).optInt("index")
                if (idx != state.optInt("preset", -1)) {
                    state.put("preset", idx)
                    events.add(JSONObject().put("t", "setPreset").put("i", idx).toString())
                    refresh()
                }
                dlg.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .create()
        show(d)
    }

    private fun dp(a: Activity, v: Float): Int = (v * a.resources.displayMetrics.density + 0.5f).toInt()
}
