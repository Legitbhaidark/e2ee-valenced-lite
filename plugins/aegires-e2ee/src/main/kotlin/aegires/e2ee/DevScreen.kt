package aegires.e2ee

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * The developer tools screen (developer builds only): Diagnostics, Self-test, Crypto benchmark and
 * Developer options, shown over the settings screen. It shares the settings screen's state and event
 * queue, so the JS side needs no extra bridge: this class only draws and reports what the user did,
 * and the JS side runs the actions (they need the JS crypto and storage) and pushes the results back.
 *
 * Events: {"t":"dev","a":"diag"|"selftest"|"measure"}  {"a":"bench","mib":<1|8|32|64>,"js":bool}
 *         {"a":"set","k":"v1"|"shortPw","v":bool}  {"a":"engine","v":"auto"|"c"|"java"}
 *         {"a":"kdfOn","v":bool}  {"a":"kdfSave","mem":<text>,"passes":<text>}
 * State it reads: dev{diagText,v1,shortPw,engine,kdfOn,kdfMem,kdfPasses,maxMem,maxPasses}, devBusy,
 * devLines (self-test / measure result), devBench (benchmark result).
 */
internal object DevScreen {
    private val S = SettingsScreen
    private var dialog: Dialog? = null
    private var activity: Activity? = null
    private var refreshing = false // true while the views are set from the state, so their listeners stay quiet

    private var diagView: TextView? = null
    private var busyView: TextView? = null
    private val runButtons = ArrayList<Button>()
    private var testBox: LinearLayout? = null
    private var benchBox: LinearLayout? = null
    private val mibs = listOf(1, 8, 32, 64)
    private val mibButtons = ArrayList<Button>()
    private var benchMib = 8
    private var benchJs = false
    private var v1Switch: Switch? = null
    private var shortSwitch: Switch? = null
    private var kdfSwitch: Switch? = null
    private val engines = listOf("auto", "c", "java")
    private val engineButtons = ArrayList<Button>()
    private var memField: EditText? = null
    private var passesField: EditText? = null

    private fun dev(): JSONObject = S.state.optJSONObject("dev") ?: JSONObject()
    private fun event(action: String): JSONObject = JSONObject().put("t", "dev").put("a", action)
    private fun send(o: JSONObject) {
        S.events.add(o.toString())
    }

    /** Opens the screen. Call on the UI thread (it is called from a button of the settings screen). */
    fun show(a: Activity) {
        try {
            dialog?.dismiss()
            val d = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            d.setContentView(build(a, d))
            d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            d.setOnDismissListener {
                if (dialog === d) {
                    dialog = null
                    activity = null
                }
            }
            dialog = d
            activity = a
            d.show()
            refresh()
            send(event("diag")) // fresh Diagnostics
        } catch (t: Throwable) {
            Toast.makeText(a, "Can't open the developer tools", Toast.LENGTH_SHORT).show()
        }
    }

    fun close() {
        try {
            dialog?.dismiss()
        } catch (_: Throwable) {
        }
        dialog = null
        activity = null
    }

    /** Sets the views from the state (called when the screen opens and after every update from JS). */
    fun refresh() {
        if (activity == null) return
        val dv = dev()
        refreshing = true
        try {
            diagView?.text = dv.optString("diagText")
            val busy = S.state.optString("devBusy", "")
            busyView?.text = busy
            busyView?.visibility = if (busy.isEmpty()) View.GONE else View.VISIBLE
            for (b in runButtons) b.isEnabled = busy.isEmpty()
            fillLines(testBox, S.state.optJSONArray("devLines")) { it.startsWith("FAIL") }
            fillLines(benchBox, S.state.optJSONArray("devBench")) { it.contains("MISMATCH") }
            v1Switch?.isChecked = dv.optBoolean("v1", false)
            shortSwitch?.isChecked = dv.optBoolean("shortPw", false)
            kdfSwitch?.isChecked = dv.optBoolean("kdfOn", false)
            val engine = dv.optString("engine", "auto")
            for (i in engines.indices) paint(engineButtons.getOrNull(i), engines[i] == engine)
            for (i in mibs.indices) paint(mibButtons.getOrNull(i), mibs[i] == benchMib)
        } finally {
            refreshing = false
        }
    }

    // ---- the cards -----------------------------------------------------------------------------

    private fun build(a: Activity, d: Dialog): View {
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(S.BG)

        val bar = LinearLayout(a)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(S.dp(a, 16f), S.dp(a, 36f), S.dp(a, 8f), S.dp(a, 8f))
        bar.addView(S.label(a, "Developer tools", S.TEXT, 20f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = S.label(a, "✕", S.TEXT, 20f, false)
        close.setPadding(S.dp(a, 16f), S.dp(a, 8f), S.dp(a, 16f), S.dp(a, 8f))
        close.setOnClickListener { d.dismiss() }
        bar.addView(close)
        root.addView(bar)

        val busy = S.label(a, "", S.ACCENT, 15f, true)
        busy.setPadding(S.dp(a, 16f), 0, S.dp(a, 16f), S.dp(a, 6f))
        busy.visibility = View.GONE
        busyView = busy
        root.addView(busy)

        runButtons.clear()
        mibButtons.clear()
        engineButtons.clear()
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(S.dp(a, 12f), S.dp(a, 4f), S.dp(a, 12f), S.dp(a, 24f))
        col.addView(diagnosticsCard(a))
        col.addView(selfTestCard(a))
        col.addView(benchCard(a))
        col.addView(optionsCard(a))
        val scroll = ScrollView(a)
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun diagnosticsCard(a: Activity): View {
        val c = S.card(a, "Diagnostics")
        c.addView(S.label(a, "What the hooks are doing right now. Press Refresh after sending / receiving a message.", S.MUTED, 14f, false), S.top(a, 4f))
        val r = S.button(a, "Refresh", S.ACCENT)
        r.setOnClickListener { send(event("diag")) }
        c.addView(r, S.top(a, 8f))
        val t = S.label(a, "", S.TEXT, 12f, false)
        t.typeface = Typeface.MONOSPACE
        t.setTextIsSelectable(true)
        c.addView(t, S.top(a, 8f))
        diagView = t
        return c
    }

    private fun selfTestCard(a: Activity): View {
        val c = S.card(a, "Self-test")
        c.addView(S.label(a, "Run this first. It checks Discord has a secure random source and that this phone can decrypt messages made by the real Aegires code.", S.MUTED, 14f, false), S.top(a, 4f))
        val run = S.button(a, "Run self-test", S.ACCENT)
        run.setOnClickListener { send(event("selftest")) }
        c.addView(run, S.top(a, 8f))
        val measure = S.button(a, "Measure key derivation speed (real size)", S.ACCENT)
        measure.setOnClickListener { send(event("measure")) }
        c.addView(measure, S.top(a, 8f))
        runButtons.add(run)
        runButtons.add(measure)
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        c.addView(box, S.top(a, 4f))
        testBox = box
        return c
    }

    private fun benchCard(a: Activity): View {
        val c = S.card(a, "Crypto benchmark")
        c.addView(S.label(a, "Times Argon2id, HKDF-SHA256 and AES-256-GCM on plain JS, Kotlin/Java and the C library. Native results are the median of 3 runs, timed inside the native half. The size below is for Argon2id. Plain JS is slow (about 0.6 s per MiB for Argon2id, and the AES test takes a while), so it is off by default.", S.MUTED, 14f, false), S.top(a, 4f))
        c.addView(chips(a, listOf("1 MiB", "8 MiB", "32 MiB", "64 MiB (real)"), mibButtons) { i ->
            benchMib = mibs[i]
            refresh()
        }, S.top(a, 8f))
        switchRow(a, c, "Include plain JS (slow)") { checked -> benchJs = checked }
        val run = S.button(a, "Run crypto benchmark", S.ACCENT)
        run.setOnClickListener { send(event("bench").put("mib", benchMib).put("js", benchJs)) }
        c.addView(run, S.top(a, 10f))
        runButtons.add(run)
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        c.addView(box, S.top(a, 4f))
        benchBox = box
        return c
    }

    private fun optionsCard(a: Activity): View {
        val c = S.card(a, "Developer options")
        val dv = dev()
        c.addView(S.label(a, "For testing only. These options make the client less secure.", S.MUTED, 14f, false), S.top(a, 4f))
        v1Switch = switchRow(a, c, "Use the v1 legacy format for sending (AES-256-GCM, PBKDF2). Older and less secure; only for talking to old Aegires versions") { v ->
            send(event("set").put("k", "v1").put("v", v))
        }
        shortSwitch = switchRow(a, c, "Allow passwords as short as 1 character instead of 8. Makes the client less secure - proceed with caution") { v ->
            send(event("set").put("k", "shortPw").put("v", v))
        }

        c.addView(S.label(a, "Argon2id engine", S.TEXT, 15f, true), S.top(a, 14f))
        c.addView(S.label(a, "Which code derives your key. Auto uses the C library, then Kotlin/Java. A fixed engine that is not available makes key derivation fail (see Diagnostics). All engines give the same key.", S.MUTED, 14f, false), S.top(a, 4f))
        c.addView(chips(a, listOf("Auto", "C library", "Kotlin/Java"), engineButtons) { i ->
            send(event("engine").put("v", engines[i]))
        }, S.top(a, 8f))

        c.addView(S.label(a, "Custom Argon2id size", S.TEXT, 15f, true), S.top(a, 14f))
        c.addView(S.label(a, "Bad idea. Lowering memory or passes makes your password much easier to crack, and keys made with other values do NOT match the Aegires app or other users: nobody could read your messages and you could not read theirs. Only do this if you know what you are doing. It applies to the next key you derive (set the password again after changing it).", S.DANGER, 14f, false), S.top(a, 4f))
        val sw = switchRow(a, c, "Force custom size (standard: 64 MiB, 3 passes)") { v ->
            if (!v) {
                send(event("kdfOn").put("v", false))
            } else {
                AlertDialog.Builder(a)
                    .setTitle("Weaker, incompatible keys")
                    .setMessage("Only continue if you know what you are doing. Messages will not be readable by anyone using the standard size.")
                    .setNegativeButton("Cancel") { _, _ -> refresh() } // puts the switch back to what JS says
                    .setPositiveButton("I know what I am doing") { _, _ -> send(event("kdfOn").put("v", true)) }
                    .setOnCancelListener { refresh() }
                    .show()
            }
        }
        kdfSwitch = sw

        val mem = field(a, "Memory in MiB (1-" + dv.optInt("maxMem", 256) + ")")
        mem.setText(dv.optInt("kdfMem", 64).toString())
        c.addView(mem, S.top(a, 10f))
        memField = mem
        val passes = field(a, "Passes (1-" + dv.optInt("maxPasses", 16) + ")")
        passes.setText(dv.optInt("kdfPasses", 3).toString())
        c.addView(passes, S.top(a, 8f))
        passesField = passes
        val save = S.button(a, "Save custom size", S.ACCENT)
        save.setOnClickListener { send(event("kdfSave").put("mem", mem.text.toString().trim()).put("passes", passes.text.toString().trim())) }
        c.addView(save, S.top(a, 10f))
        return c
    }

    // ---- small view helpers --------------------------------------------------------------------

    private fun field(a: Activity, hint: String): EditText {
        val e = EditText(a)
        e.hint = hint
        e.setHintTextColor(S.MUTED)
        e.setTextColor(S.TEXT)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setBackgroundColor(S.INPUT)
        e.setPadding(S.dp(a, 12f), S.dp(a, 10f), S.dp(a, 12f), S.dp(a, 10f))
        return e
    }

    /** A label with a switch; onChange only hears the user, not refresh(). */
    private fun switchRow(a: Activity, parent: LinearLayout, text: String, onChange: (Boolean) -> Unit): Switch {
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(S.label(a, text, S.TEXT, 15f, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(a)
        sw.setOnCheckedChangeListener { _, checked -> if (!refreshing) onChange(checked) }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = S.dp(a, 12f)
        row.addView(sw, lp)
        parent.addView(row, S.top(a, 10f))
        return sw
    }

    /** Buttons in rows of two. The chosen one is painted in the accent color by refresh(). */
    private fun chips(a: Activity, labels: List<String>, out: ArrayList<Button>, onPick: (Int) -> Unit): View {
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        var row: LinearLayout? = null
        for ((i, text) in labels.withIndex()) {
            if (i % 2 == 0) {
                row = LinearLayout(a)
                row.orientation = LinearLayout.HORIZONTAL
                col.addView(row, S.top(a, 6f))
            }
            val b = S.button(a, text, S.INPUT)
            b.setOnClickListener { onPick(i) }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i % 2 == 0) lp.rightMargin = S.dp(a, 6f)
            row!!.addView(b, lp)
            out.add(b)
        }
        return col
    }

    private fun paint(b: Button?, chosen: Boolean) {
        (b?.background as? GradientDrawable)?.setColor(if (chosen) S.ACCENT else S.INPUT)
    }

    private fun fillLines(box: LinearLayout?, lines: JSONArray?, danger: (String) -> Boolean) {
        val a = activity ?: return
        if (box == null) return
        box.removeAllViews()
        if (lines == null) return
        for (i in 0 until lines.length()) {
            val line = lines.optString(i)
            if (line.isEmpty()) {
                box.addView(View(a), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, S.dp(a, 8f)))
                continue
            }
            val t = S.label(a, line, if (danger(line)) S.DANGER else S.TEXT, 13f, false)
            t.typeface = Typeface.MONOSPACE
            t.setTextIsSelectable(true)
            box.addView(t, S.top(a, 3f))
        }
    }
}
