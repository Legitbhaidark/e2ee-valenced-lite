package aegires.e2ee

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The plugin's settings screen, drawn natively (a full-screen dialog). The JS side keeps the real
 * settings and does the actions that need JS (storage, deriving keys); this class only draws the cards
 * and reports what the user did:
 *
 *   JS -> here   settingsopen(stateJson)   opens the screen with the current values
 *                settingsupdate(partial)    changes values while it is open (key set, progress text)
 *   here -> JS   settingstake()             {"open":bool,"events":[...]} - JS polls this, like the header button
 *
 * Events: {"t":"set","k":<key>,"v":<bool|string>}  {"t":"setPreset","i":<preset index>}
 *         {"t":"setKey","pw":<password>}  {"t":"clearKey"}  {"t":"dev",...} (dev builds: see DevScreen)
 *         {"t":"saveChat","id":<channel>,"pw":<optional>}  {"t":"chatEnabled","id":..,"v":bool}  {"t":"removeChat","id":..}
 */
internal object SettingsScreen {
    internal const val BG = 0xFF1E1F22.toInt()
    internal const val CARD = 0xFF2B2D31.toInt()
    internal const val INPUT = 0xFF383A40.toInt()
    internal const val TEXT = 0xFFDBDEE1.toInt()
    internal const val MUTED = 0xFF949BA4.toInt()
    internal const val ACCENT = 0xFF5865F2.toInt()
    internal const val DANGER = 0xFFDA373C.toInt()

    internal val events = ConcurrentLinkedQueue<String>()
    private var dialog: Dialog? = null
    private var activity: Activity? = null
    internal var state = JSONObject()

    @Volatile
    private var open = false

    // Views that change while the screen is open.
    private var keyTitle: TextView? = null
    private var keyButton: Button? = null
    private var clearButton: Button? = null
    private var passwordField: EditText? = null
    private var formatTitle: TextView? = null
    private val presetButtons = ArrayList<PresetButton>()
    private var chatIdField: EditText? = null
    private var chatPwField: EditText? = null
    private var chatSaveButton: Button? = null
    private var chatListBox: LinearLayout? = null

    private class PresetButton(val index: Int, val label: String, val max: Int, val button: Button)

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
                dialog?.dismiss()
                events.clear()
                Glass.on = state.optJSONObject("values")?.optBoolean("liquidGlass", false) == true
                val d = Dialog(a, if (Glass.on) android.R.style.Theme_Translucent_NoTitleBar else android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                d.setContentView(build(a, d))
                d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                if (Glass.on && android.os.Build.VERSION.SDK_INT >= 31) {
                    d.window?.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    d.window?.attributes?.blurBehindRadius = 60
                }
                d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                d.setOnDismissListener {
                    if (dialog === d) {
                        DevScreen.close()
                        dialog = null
                        activity = null
                        open = false
                    }
                }
                dialog = d
                activity = a
                open = true
                d.show()
            } catch (t: Throwable) {
                open = false
                Toast.makeText(a, "Can't open the settings screen", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Merges changed values into the state and refreshes the views that show them. */
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
                refreshKeyCard()
                refreshFormatCard()
                refreshChatCard()
                DevScreen.refresh()
            } catch (_: Throwable) {
            }
        }
    }

    fun close() {
        DevScreen.close()
        try {
            dialog?.dismiss()
        } catch (_: Throwable) {
        }
        dialog = null
        activity = null
        open = false
    }

    // ---- the cards -----------------------------------------------------------------------------

    private fun build(a: Activity, d: Dialog): View {
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(if (Glass.on) 0xB0101114.toInt() else BG)

        val bar = LinearLayout(a)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(a, 16f), dp(a, 36f), dp(a, 8f), dp(a, 8f))
        val title = label(a, "Aegires E2EE settings", TEXT, 20f, true)
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = label(a, "✕", TEXT, 20f, false)
        close.setPadding(dp(a, 16f), dp(a, 8f), dp(a, 16f), dp(a, 8f))
        close.setOnClickListener { d.dismiss() }
        bar.addView(close)
        root.addView(bar)

        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(a, 12f), dp(a, 4f), dp(a, 12f), dp(a, 24f))
        col.addView(keyCard(a))
        col.addView(formatCard(a))
        builtinCard(a)?.let { col.addView(it) }
        col.addView(behaviourCard(a))
        col.addView(chatCard(a))
        if (state.optBoolean("isDev", false)) col.addView(toolsCard(a))
        col.addView(versionsCard(a))
        val scroll = ScrollView(a)
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        keyboardAware(a, root)
        return root
    }

    /**
     * Keeps the text box you are typing in above the keyboard. Full-screen dialogs often ignore
     * adjustResize, so the keyboard would cover the box: measure how much of the screen it takes and
     * pad the bottom of the screen by that much (when the system already resized the window, nothing is added).
     */
    internal fun keyboardAware(a: Activity, root: View) {
        val r = android.graphics.Rect()
        var last = -1
        root.viewTreeObserver.addOnGlobalLayoutListener {
            root.getWindowVisibleDisplayFrame(r)
            val screenH = root.rootView.height
            val kb = (screenH - r.bottom).coerceAtLeast(0)
            val pad = if (kb > screenH / 6) kb else 0
            if (pad != last) {
                last = pad
                root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, pad)
                root.postDelayed({
                    val f = root.findFocus()
                    if (f is EditText) f.requestRectangleOnScreen(android.graphics.Rect(0, 0, f.width, f.height + dp(a, 24f)), false)
                }, 120)
            }
        }
    }

    private fun keyCard(a: Activity): View {
        val c = card(a, "")
        keyTitle = c.getChildAt(0) as TextView
        c.addView(label(a, "Used in every chat that has no channel key. Everyone in the chat must type the SAME password (at least " + state.optString("rule", "8 characters") + "), and Aegires users must use the same one in the app. The password is not saved - only the keys derived from it. Deriving the v2 key (Argon2id, 64 MiB) can take a while on a phone.", MUTED, 14f, false), top(a, 4f))

        val pw = EditText(a)
        pw.hint = "Shared password"
        pw.setHintTextColor(MUTED)
        pw.setTextColor(TEXT)
        pw.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        styleInput(a, pw)
        c.addView(pw, top(a, 10f))
        passwordField = pw

        val set = button(a, "Set global key", ACCENT)
        set.setOnClickListener {
            val text = pw.text.toString()
            if (text.isEmpty()) {
                Toast.makeText(a, "Password cannot be empty", Toast.LENGTH_SHORT).show()
            } else {
                events.add(JSONObject().put("t", "setKey").put("pw", text).toString())
                pw.setText("")
            }
        }
        c.addView(set, top(a, 10f))
        keyButton = set

        val clear = button(a, "Clear global key", DANGER)
        clear.setOnClickListener { events.add(JSONObject().put("t", "clearKey").toString()) }
        c.addView(clear, top(a, 8f))
        clearButton = clear

        refreshKeyCard()
        return c
    }

    private fun refreshKeyCard() {
        val has = state.optBoolean("hasKey", false)
        val busy = state.optBoolean("busy", false)
        keyTitle?.text = "Global key " + (if (has) "(set)" else "(not set)")
        keyButton?.text = if (busy) state.optString("status", "Working...") else "Set global key"
        keyButton?.isEnabled = !busy
        clearButton?.visibility = if (has && !busy) View.VISIBLE else View.GONE
    }

    private fun formatCard(a: Activity): View {
        val c = card(a, "")
        formatTitle = c.getChildAt(0) as TextView
        c.addView(label(a, "v2 is the current Aegires format: Argon2id keys, a fresh key per message and per file bound to the chat and the sender. Old v1 messages are no longer supported.", MUTED, 14f, false), top(a, 4f))
        presetButtons.clear()
        val presets = state.optJSONArray("presets") ?: JSONArray()
        for (i in 0 until presets.length()) {
            val p = presets.getJSONObject(i)
            val index = p.getInt("index")
            val b = button(a, "", ACCENT)
            b.setOnClickListener {
                state.put("preset", index)
                events.add(JSONObject().put("t", "setPreset").put("i", index).toString())
                refreshFormatCard()
            }
            c.addView(b, top(a, 8f))
            presetButtons.add(PresetButton(index, p.optString("label"), p.optInt("max"), b))
        }
        toggle(a, c, state.optJSONObject("values") ?: JSONObject(), "padme", "Hide message length better (Padmé padding, up to ~12% longer; every v2 client can read it)")
        refreshFormatCard()
        return c
    }

    private fun refreshFormatCard() {
        val cur = state.optInt("preset", -1)
        val curLabel = presetButtons.firstOrNull { it.index == cur }?.label ?: ""
        formatTitle?.text = "Format: v2, $curLabel"
        for (p in presetButtons) {
            p.button.text = (if (p.index == cur) "✔ " else "") + "v2 " + p.label + "  (max " + p.max + " bytes)"
        }
    }

    private fun builtinCard(a: Activity): View? {
        val ids = state.optJSONArray("forced") ?: return null
        if (ids.length() == 0) return null
        val c = card(a, "Built-in channels")
        c.addView(label(a, "Aegires channels with a built-in key: always encrypted with v2 AES-128-GCM, nothing to set up. Anyone with Aegires can read them.", MUTED, 14f, false), top(a, 4f))
        for (i in 0 until ids.length()) {
            val t = label(a, ids.getString(i), TEXT, 15f, false)
            t.setTextIsSelectable(true)
            t.typeface = Typeface.MONOSPACE
            c.addView(t, top(a, 6f))
        }
        return c
    }

    private fun behaviourCard(a: Activity): View {
        val c = card(a, "Behaviour")
        val v = state.optJSONObject("values") ?: JSONObject()
        toggle(a, c, v, "encryptByDefault", "Encrypt outgoing messages by default (in chats without their own on/off setting)")
        toggle(a, c, v, "attachmentsExp", "Attachments: encrypt files you send in encrypted chats (up to 1 GiB), under random names. Received encrypted images, GIFs and videos are shown decrypted. When off, uploads are blocked in encrypted chats")
        toggle(a, c, v, "liquidGlass", "Liquid glass look for the media players and this screen (frosted, see-through panels; on Android 12+ this screen also blurs what is behind it). Applies to players you open next and to this screen the next time you open it")
        toggle(a, c, v, "linkPreviews", "Link previews (EXPERIMENTAL): show a title, description and picture under the first https link of a decrypted message. Discord cannot make these for encrypted text, so THIS PHONE contacts the linked site: the site sees your IP address and when you opened the chat, and the picture loads from the site directly. Off by default")

        c.addView(label(a, "Encrypted files larger than this many MB show \"Tap to decrypt\" instead of downloading on their own (0 = always ask, default 25):", TEXT, 15f, false), top(a, 10f))
        val mb = EditText(a)
        mb.hint = "MB (default 25)"
        mb.setHintTextColor(MUTED)
        mb.setTextColor(TEXT)
        mb.inputType = InputType.TYPE_CLASS_NUMBER
        styleInput(a, mb)
        mb.setText(state.optInt("autoMb", 25).toString())
        mb.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                events.add(JSONObject().put("t", "set").put("k", "autoDecryptMB").put("v", s?.toString() ?: "").toString())
            }
        })
        c.addView(mb, top(a, 6f))

        toggle(a, c, v, "inlineVideo", "Play videos in the chat (on their picture) instead of full screen")
        toggle(a, c, v, "encButton", "Show the \uD83D\uDD12 E2EE button in the chat header (left of the search icon, and left of the call icons in a DM). Tap it to switch encryption on or off for that chat: \uD83D\uDD12 E2EE = on, \uD83D\uDD12 OFF = off")
        toggle(a, c, v, "showLock", "Show a lock icon next to the time of decrypted messages")
        if (state.optBoolean("isDev", false)) toggle(a, c, v, "debug", "Debug logging")
        return c
    }

    /** Rounded text box that scrolls itself into view when the keyboard opens (the screen resizes for the keyboard). */
    internal fun styleInput(a: Activity, e: EditText) {
        val bg = GradientDrawable()
        bg.setColor(INPUT)
        bg.cornerRadius = dp(a, 14f).toFloat()
        e.background = bg
        e.setPadding(dp(a, 14f), dp(a, 12f), dp(a, 14f), dp(a, 12f))
        val reveal = Runnable {
            if (e.isAttachedToWindow) e.requestRectangleOnScreen(android.graphics.Rect(0, 0, e.width, e.height + dp(a, 24f)), false)
        }
        // The keyboard needs a moment to open and resize the screen; reveal again once it has.
        e.setOnFocusChangeListener { v, focused -> if (focused) { v.postDelayed(reveal, 150); v.postDelayed(reveal, 450) } }
        e.setOnClickListener { e.postDelayed(reveal, 150); e.postDelayed(reveal, 450) }
    }

    private fun field(a: Activity, hint: String, secret: Boolean, digits: Boolean): EditText {
        val e = EditText(a)
        e.hint = hint
        e.setHintTextColor(MUTED)
        e.setTextColor(TEXT)
        e.inputType = when {
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            digits -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT
        }
        styleInput(a, e)
        return e
    }

    private fun chatCard(a: Activity): View {
        val c = card(a, "Per-chat key / switch")
        c.addView(label(a, "Channel ID is pre-filled with the chat you had open. Leave the password empty to only change on/off.", MUTED, 14f, false), top(a, 4f))
        val id = field(a, "Channel ID", secret = false, digits = true)
        id.setText(state.optString("chanId"))
        c.addView(id, top(a, 10f))
        chatIdField = id
        val pw = field(a, "Password for this chat only (optional)", secret = true, digits = false)
        c.addView(pw, top(a, 8f))
        chatPwField = pw
        val save = button(a, "Save for this chat", ACCENT)
        save.setOnClickListener {
            val chan = id.text.toString().trim()
            if (!Regex("\\d+").matches(chan)) {
                Toast.makeText(a, "Channel ID must be digits only", Toast.LENGTH_SHORT).show()
            } else {
                events.add(JSONObject().put("t", "saveChat").put("id", chan).put("pw", pw.text.toString()).toString())
                pw.setText("")
            }
        }
        c.addView(save, top(a, 10f))
        chatSaveButton = save
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        c.addView(box, top(a, 4f))
        chatListBox = box
        refreshChatCard()
        return c
    }

    private fun refreshChatCard() {
        val a = activity ?: return
        val busy = state.optBoolean("chatBusy", false)
        chatSaveButton?.text = if (busy) state.optString("chatStatus", "Working...") else "Save for this chat"
        chatSaveButton?.isEnabled = !busy
        val box = chatListBox ?: return
        box.removeAllViews()
        val arr = state.optJSONArray("chats") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val chan = o.getString("id")
            val line = View(a)
            line.setBackgroundColor(INPUT)
            box.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 1f)).also { it.topMargin = dp(a, 12f) })
            box.addView(label(a, chan + (if (o.optBoolean("hasKey", false)) "  (own key)" else ""), TEXT, 15f, false), top(a, 8f))
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(label(a, "Encrypt outgoing here", TEXT, 15f, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sw = Switch(a)
            sw.isChecked = o.optBoolean("enabled", false)
            sw.setOnCheckedChangeListener { _, checked ->
                o.put("enabled", checked)
                events.add(JSONObject().put("t", "chatEnabled").put("id", chan).put("v", checked).toString())
            }
            row.addView(sw)
            box.addView(row, top(a, 8f))
            val rm = button(a, "Remove", DANGER)
            rm.setOnClickListener {
                events.add(JSONObject().put("t", "removeChat").put("id", chan).toString())
                state.optJSONArray("chats")?.let { list ->
                    for (j in 0 until list.length()) {
                        if (list.getJSONObject(j).optString("id") == chan) {
                            list.remove(j)
                            break
                        }
                    }
                }
                refreshChatCard()
            }
            box.addView(rm, top(a, 8f))
        }
    }

    private fun toolsCard(a: Activity): View {
        val c = card(a, "Developer tools")
        c.addView(label(a, "Diagnostics, self-test, crypto benchmark and developer options (dev builds only).", MUTED, 14f, false), top(a, 4f))
        val b = button(a, "Open developer tools", ACCENT)
        b.setOnClickListener { DevScreen.show(a) }
        c.addView(b, top(a, 10f))
        return c
    }

    private fun versionsCard(a: Activity): View {
        val c = card(a, "Plugin versions")
        c.addView(label(a, "Developer: " + state.optString("developer"), TEXT, 15f, false), top(a, 8f))
        c.addView(label(a, "Version: " + state.optString("version"), TEXT, 15f, false), top(a, 4f))
        c.addView(label(a, "Build number: " + state.optString("build"), TEXT, 15f, false), top(a, 4f))
        c.addView(label(a, "Message format: Aegires v2", MUTED, 14f, false), top(a, 8f))
        c.addView(label(a, "File format: Aegires v2", MUTED, 14f, false), top(a, 4f))
        c.addView(label(a, "Native module: " + state.optString("native"), MUTED, 14f, false), top(a, 4f))
        c.addView(label(a, "Argon2id engine: " + NativeArgon2.status(), MUTED, 14f, false), top(a, 4f))
        return c
    }

    // ---- small view helpers --------------------------------------------------------------------

    private fun toggle(a: Activity, parent: LinearLayout, values: JSONObject, key: String, text: String) {
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(label(a, text, TEXT, 15f, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(a)
        sw.isChecked = values.optBoolean(key, false)
        sw.setOnCheckedChangeListener { _, checked ->
            values.put(key, checked)
            if (key == "liquidGlass") Glass.on = checked
            events.add(JSONObject().put("t", "set").put("k", key).put("v", checked).toString())
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = dp(a, 12f)
        row.addView(sw, lp)
        parent.addView(row, top(a, 10f))
    }

    internal fun card(a: Activity, title: String): LinearLayout {
        val l = LinearLayout(a)
        l.orientation = LinearLayout.VERTICAL
        if (Glass.on) {
            l.background = Glass.drawable(dp(a, 14f).toFloat())
        } else {
            val bg = GradientDrawable()
            bg.setColor(CARD)
            bg.cornerRadius = dp(a, 8f).toFloat()
            l.background = bg
        }
        l.setPadding(dp(a, 12f), dp(a, 12f), dp(a, 12f), dp(a, 12f))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(a, 12f)
        l.layoutParams = lp
        l.addView(label(a, title, TEXT, 17f, true)) // child 0 is the title (the key card changes its text)
        return l
    }

    internal fun label(a: Activity, text: String, color: Int, size: Float, bold: Boolean): TextView {
        val t = TextView(a)
        t.text = text
        t.setTextColor(color)
        t.textSize = size
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    internal fun button(a: Activity, text: String, color: Int): Button {
        val b = Button(a)
        b.text = text
        b.setAllCaps(false)
        b.setTextColor(Color.WHITE)
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = dp(a, 6f).toFloat()
        b.background = bg
        return b
    }

    internal fun top(a: Activity, margin: Float): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(a, margin)
        return lp
    }

    internal fun dp(a: Activity, v: Float): Int = (v * a.resources.displayMetrics.density + 0.5f).toInt()
}
