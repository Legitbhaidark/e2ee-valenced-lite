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
 * Events: {"t":"set","k":<key>,"v":<bool|string>}  {"t":"setKey","pw":<password>}  {"t":"clearKey"}
 */
internal object SettingsScreen {
    private const val BG = 0xFF1E1F22.toInt()
    private const val CARD = 0xFF2B2D31.toInt()
    private const val INPUT = 0xFF383A40.toInt()
    private const val TEXT = 0xFFDBDEE1.toInt()
    private const val MUTED = 0xFF949BA4.toInt()
    private const val ACCENT = 0xFF5865F2.toInt()
    private const val DANGER = 0xFFDA373C.toInt()

    private val events = ConcurrentLinkedQueue<String>()
    private var dialog: Dialog? = null
    private var activity: Activity? = null
    private var state = JSONObject()

    @Volatile
    private var open = false

    // Views that change while the screen is open.
    private var keyTitle: TextView? = null
    private var keyButton: Button? = null
    private var clearButton: Button? = null
    private var passwordField: EditText? = null

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
                val d = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                d.setContentView(build(a, d))
                d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                d.setOnDismissListener {
                    if (dialog === d) {
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
            } catch (_: Throwable) {
            }
        }
    }

    fun close() {
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
        root.setBackgroundColor(BG)

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
        col.addView(howToCard(a))
        col.addView(keyCard(a))
        col.addView(behaviourCard(a))
        col.addView(versionsCard(a))
        val scroll = ScrollView(a)
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun howToCard(a: Activity): View {
        val c = card(a, "How to use")
        c.addView(label(a, "1. Set the shared password", TEXT, 15f, true), top(a, 8f))
        c.addView(label(a, "Type it in \"Global key\" below and press Set global key. Everyone in the chat needs the SAME password; Aegires app users type it in the app. Only the derived keys are stored, never the password.", MUTED, 14f, false), top(a, 2f))
        c.addView(label(a, "2. Turn encryption on in a chat", TEXT, 15f, true), top(a, 10f))
        c.addView(label(a, "Use the E2EE button next to the search icon, or send a command as a normal message (it is never sent to Discord):", MUTED, 14f, false), top(a, 2f))
        val cmd = label(a, "/e2ee on      encrypt this chat\n/e2ee off     send plain text again\n/e2ee status  is it on here?", TEXT, 13f, false)
        cmd.typeface = Typeface.MONOSPACE
        cmd.setTextIsSelectable(true)
        c.addView(cmd, top(a, 4f))
        c.addView(label(a, "You can also type / and pick \"e2ee\" from Discord's command list, or use a backslash: \\e2ee on.", MUTED, 14f, false), top(a, 4f))
        c.addView(label(a, "3. Chat normally", TEXT, 15f, true), top(a, 10f))
        c.addView(label(a, "Your messages go out encrypted (a § and random text). Messages from others are decrypted for you, with a lock next to the time. Images, GIFs, videos and audio work too: tap a decrypted video to play it. Turn on \"Attachments\" in Behaviour to encrypt files you send.", MUTED, 14f, false), top(a, 2f))
        return c
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
        pw.setBackgroundColor(INPUT)
        pw.setPadding(dp(a, 12f), dp(a, 10f), dp(a, 12f), dp(a, 10f))
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

    private fun behaviourCard(a: Activity): View {
        val c = card(a, "Behaviour")
        val v = state.optJSONObject("values") ?: JSONObject()
        toggle(a, c, v, "encryptByDefault", "Encrypt outgoing messages by default (in chats without their own on/off setting)")
        toggle(a, c, v, "attachmentsExp", "Attachments: encrypt files you send in encrypted chats (up to 1 GiB), under random names. Received encrypted images, GIFs and videos are shown decrypted. When off, uploads are blocked in encrypted chats")
        toggle(a, c, v, "linkPreviews", "Link previews (EXPERIMENTAL): show a title, description and picture under the first https link of a decrypted message. Discord cannot make these for encrypted text, so THIS PHONE contacts the linked site: the site sees your IP address and when you opened the chat, and the picture loads from the site directly. Off by default")

        c.addView(label(a, "Encrypted files larger than this many MB show \"Tap to decrypt\" instead of downloading on their own (0 = always ask, default 25):", TEXT, 15f, false), top(a, 10f))
        val mb = EditText(a)
        mb.hint = "MB (default 25)"
        mb.setHintTextColor(MUTED)
        mb.setTextColor(TEXT)
        mb.inputType = InputType.TYPE_CLASS_NUMBER
        mb.setBackgroundColor(INPUT)
        mb.setPadding(dp(a, 12f), dp(a, 10f), dp(a, 12f), dp(a, 10f))
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
        toggle(a, c, v, "encButton", "Show an E2EE on/off button in the chat header, left of the search icon")
        toggle(a, c, v, "showLock", "Show a lock icon next to the time of decrypted messages")
        if (state.optBoolean("isDev", false)) toggle(a, c, v, "debug", "Debug logging")
        return c
    }

    private fun versionsCard(a: Activity): View {
        val c = card(a, "Plugin versions")
        c.addView(label(a, "Developer: " + state.optString("developer"), TEXT, 15f, false), top(a, 8f))
        c.addView(label(a, "Version: " + state.optString("version"), TEXT, 15f, false), top(a, 4f))
        c.addView(label(a, "Build number: " + state.optString("build"), TEXT, 15f, false), top(a, 4f))
        c.addView(label(a, "Message format: Aegires v2 (v1 read-only)", MUTED, 14f, false), top(a, 8f))
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
            events.add(JSONObject().put("t", "set").put("k", key).put("v", checked).toString())
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = dp(a, 12f)
        row.addView(sw, lp)
        parent.addView(row, top(a, 10f))
    }

    private fun card(a: Activity, title: String): LinearLayout {
        val l = LinearLayout(a)
        l.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(CARD)
        bg.cornerRadius = dp(a, 8f).toFloat()
        l.background = bg
        l.setPadding(dp(a, 12f), dp(a, 12f), dp(a, 12f), dp(a, 12f))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(a, 12f)
        l.layoutParams = lp
        l.addView(label(a, title, TEXT, 17f, true)) // child 0 is the title (the key card changes its text)
        return l
    }

    private fun label(a: Activity, text: String, color: Int, size: Float, bold: Boolean): TextView {
        val t = TextView(a)
        t.text = text
        t.setTextColor(color)
        t.textSize = size
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun button(a: Activity, text: String, color: Int): Button {
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

    private fun top(a: Activity, margin: Float): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(a, margin)
        return lp
    }

    private fun dp(a: Activity, v: Float): Int = (v * a.resources.displayMetrics.density + 0.5f).toInt()
}
