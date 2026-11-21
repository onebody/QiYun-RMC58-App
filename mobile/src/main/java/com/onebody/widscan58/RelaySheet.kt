// The wireless relay's page: whether the phone is listening, which address the relay
// should dial, and what is attached right now. GPL-3.0-or-later.
package com.onebody.widscan58

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class RelaySheet(
    private val activity: Activity,
    private val state: () -> State,
    private val onStart: (Int) -> Unit,
    private val onStop: () -> Unit,
) {
    data class State(
        val listening: Boolean,
        val port: Int,
        val addresses: List<String>,
        val peer: String?,
        /** A USB board owns the wire, so an incoming relay would be turned away. */
        val usb: Boolean,
    )

    private var dialog: AlertDialog? = null
    private lateinit var statusLine: TextView
    private lateinit var addressLine: TextView
    private lateinit var peerLine: TextView
    private lateinit var hintLine: TextView
    private lateinit var portField: EditText
    private lateinit var toggle: Button

    fun show() {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        statusLine = TextView(activity).apply { textSize = 15f }
        addressLine = TextView(activity).apply { textSize = 13f }
        peerLine = TextView(activity).apply { textSize = 13f }
        hintLine = TextView(activity).apply {
            textSize = 12f
            alpha = 0.7f
            setPadding(0, pad / 2, 0, 0)
        }
        portField = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(state().port.toString())
        }
        toggle = Button(activity).apply {
            setOnClickListener {
                val current = state()
                if (current.listening) {
                    onStop()
                } else {
                    val typed = portField.text.toString().toIntOrNull()
                    onStart(typed?.takeIf { it in PORT_MIN..PORT_MAX } ?: current.port)
                }
                update()
            }
        }
        box.addView(statusLine)
        box.addView(addressLine)
        box.addView(peerLine)
        box.addView(portField)
        box.addView(toggle)
        box.addView(hintLine)

        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.relay_title)
            .setView(box)
            .setPositiveButton(R.string.close, null)
            .create()
        dialog?.show()
        update()
    }

    fun update() {
        if (dialog?.isShowing != true) return
        val s = state()
        statusLine.text = activity.getString(
            if (s.listening) R.string.relay_status_on else R.string.relay_status_off,
            s.port,
        )
        addressLine.text = if (s.addresses.isEmpty()) {
            activity.getString(R.string.relay_no_address)
        } else {
            activity.getString(R.string.relay_address, s.addresses.joinToString("  "))
        }
        peerLine.text = when {
            s.usb -> activity.getString(R.string.relay_usb_active)
            s.peer != null -> activity.getString(R.string.relay_peer_now, s.peer)
            s.listening -> activity.getString(R.string.relay_waiting)
            else -> ""
        }
        peerLine.visibility = if (peerLine.text.isEmpty()) View.GONE else View.VISIBLE
        toggle.setText(if (s.listening) R.string.relay_stop else R.string.relay_start)
        portField.isEnabled = !s.listening
        hintLine.text = activity.getString(
            R.string.relay_hint,
            s.addresses.firstOrNull() ?: "<ip>",
            s.port,
        )
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private companion object {
        const val PORT_MIN = 1024
        const val PORT_MAX = 65535
    }
}
