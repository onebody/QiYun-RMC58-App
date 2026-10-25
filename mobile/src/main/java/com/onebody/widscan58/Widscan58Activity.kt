// Handheld spectrum instrument: USB CDC link to the board, the shared engine in
// :core, and the chart/tiles/controls this screen draws. GPL-3.0-or-later.
package com.onebody.widscan58

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import com.onebody.widscan58.core.Bands
import com.onebody.widscan58.core.Cancellable
import com.onebody.widscan58.core.CommandResult
import com.onebody.widscan58.core.CommandRunner
import com.onebody.widscan58.core.DeviceCommands
import com.onebody.widscan58.core.DropWindow
import com.onebody.widscan58.core.GainMode
import com.onebody.widscan58.core.LinkRate
import com.onebody.widscan58.core.LogDir
import com.onebody.widscan58.core.Scheduler
import com.onebody.widscan58.core.StreamItem
import com.onebody.widscan58.core.StreamParser
import com.onebody.widscan58.core.Store
import com.onebody.widscan58.core.channelTile
import com.onebody.widscan58.core.restoreCommands
import com.onebody.widscan58.usb.CdcLayout
import com.onebody.widscan58.usb.UsbAccess
import com.onebody.widscan58.usb.UsbSerialLink
import com.onebody.widscan58.usb.cdcLayoutOf
import com.onebody.widscan58.usb.findBoard
import com.onebody.widscan58.usb.usbExtra
import com.onebody.widscan58.view.ChannelTileView
import com.onebody.widscan58.view.PanoramaView
import com.onebody.widscan58.view.Style
import kotlin.math.roundToInt

class Widscan58Activity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    /** Timeouts run on the main looper; the parser never blocks on a reply. */
    private val scheduler = Scheduler { delayMs, task ->
        val runnable = Runnable { task() }
        main.postDelayed(runnable, delayMs)
        Cancellable { main.removeCallbacks(runnable) }
    }

    private val prefs by lazy { Prefs(this) }
    private val store = Store({ SystemClock.elapsedRealtime() }, JsonReplies())

    private lateinit var theme: Style
    private lateinit var panorama: PanoramaView
    private lateinit var tiles: List<ChannelTileView>
    private lateinit var tileGrid: View
    private lateinit var bandRow: LinearLayout
    private lateinit var bandChips: List<Pair<String, Button>>
    private lateinit var statusLine: TextView
    private lateinit var scanButton: Button
    private lateinit var holdsButton: Button
    private lateinit var setupButton: Button
    private lateinit var gainBar: SeekBar
    private lateinit var gainValue: TextView
    private lateinit var maxChip: Button
    private lateinit var avgChip: Button
    private lateinit var moreChip: Button
    private var sheet: SettingsSheet? = null
    private var history: HistorySheet? = null
    private var telemetry: TelemetrySheet? = null
    private var console: ConsoleSheet? = null
    private var gpio: GpioSheet? = null

    /** The web card derives these from the counters, and does it whether or not it is on screen. */
    private val linkRate = LinkRate()
    private val dropWindow = DropWindow()

    private var link: UsbSerialLink? = null
    private var sim: SimFeed? = null
    private var runner: CommandRunner? = null
    private var parser: StreamParser? = null
    private var selected = -1
    private var zoom: ZoomSheet? = null
    private var draggingGain = false
    private var lastFrameMs = 0L
    private var drawnVersion = -1L
    private var note = ""
    private var showSim = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_instrument)
        padContentForSystemBars()
        theme = Style(this)
        statusLine = findViewById(R.id.status)
        bandRow = findViewById(R.id.bands)
        panorama = findViewById(R.id.panorama)
        tileGrid = findViewById(R.id.tiles)
        scanButton = findViewById(R.id.scan)
        holdsButton = findViewById(R.id.holds)
        gainBar = findViewById(R.id.gain)
        gainValue = findViewById(R.id.gainValue)
        tiles = TILE_IDS.map { findViewById<ChannelTileView>(it) }

        panorama.store = store
        panorama.onPick = { selected = it; refresh() }
        panorama.onFit = { lo, hi ->
            prefs.dbMin = lo.roundToInt()
            prefs.dbMax = hi.roundToInt()
            refresh()
        }

        bandChips = Bands.ALL.map { band ->
            val button = chip(band.letter).apply {
                contentDescription = band.name
                setOnClickListener { selectBand(band.letter) }
            }
            bandRow.addView(button)
            band.letter to button
        }
        bandRow.addView(spacer())
        maxChip = chip(getString(R.string.chip_max)).apply {
            setOnClickListener { prefs.showMaxHold = !prefs.showMaxHold; refresh() }
        }
        avgChip = chip(getString(R.string.chip_avg)).apply {
            setOnClickListener { prefs.showAverage = !prefs.showAverage; refresh() }
        }
        setupButton = chip(getString(R.string.chip_settings)).apply {
            contentDescription = getString(R.string.settings_title)
            setOnClickListener { openSetup() }
        }
        // The row is full at six bands plus the live controls, so the whole-screen
        // views — history, telemetry, and the ones after them — come from a menu.
        moreChip = chip(getString(R.string.chip_more)).apply {
            contentDescription = getString(R.string.more_views)
            setOnClickListener { openMore() }
        }
        bandRow.addView(maxChip)
        bandRow.addView(avgChip)
        bandRow.addView(setupButton)
        bandRow.addView(moreChip)
        sheet = SettingsSheet(this, prefs, ::applySetting, ::refresh, ::planNames, ::applyPlan)

        // A tile is a button: it opens the channel's own chart. The panorama stays a scrubber.
        tiles.forEach { it.onPick = { id -> selected = id; refresh(); openZoom(id) } }
        scanButton.setOnClickListener { toggleScan() }
        holdsButton.text = getString(R.string.holds)
        holdsButton.setOnClickListener { store.resetHolds() }
        gainBar.max = Prefs.GAIN_MAX
        gainBar.progress = prefs.gain
        gainValue.text = prefs.gain.toString()
        gainBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                draggingGain = fromUser
                gainValue.text = value.toString()
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                draggingGain = true
            }

            // One command when the finger lifts, not one per pixel of travel.
            override fun onStopTrackingTouch(bar: SeekBar) {
                draggingGain = false
                applyGain(bar.progress)
            }
        })

        statusLine.text = getString(R.string.plug_in)
        // `adb shell am start -n com.onebody.widscan58/.Widscan58Activity --ez sim true`
        showSim = intent.getBooleanExtra("sim", false)
        if (showSim) {
            sim = SimFeed(store, main, ::refresh).also { it.start() }
            // The same restore a board gets, so the sim comes up in the stored state.
            handshake()
        }
    }

    override fun onResume() {
        super.onResume()
        main.post(drawLoop)
        main.postDelayed(watchdog, WATCHDOG_PERIOD_MS)
        if (showSim) sim?.start()
        if (link == null) findBoard(usbManager())?.let(::requestAndOpen)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The USB_DEVICE_ATTACHED filter launches or raises us with the device in hand.
        (intent.usbExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: findBoard(usbManager()))
            ?.let(::requestAndOpen)
    }

    override fun onPause() {
        super.onPause()
        sheet?.dismiss()
        zoom?.dismiss()
        history?.dismiss()
        telemetry?.dismiss()
        console?.dismiss()
        gpio?.dismiss()
        main.removeCallbacks(drawLoop)
        main.removeCallbacks(watchdog)
        sim?.stop()
        closeLink(getString(R.string.note_paused))
    }

    // ---- drawing: one coalesced refresh per frame, only when the data changed ----

    private val drawLoop = object : Runnable {
        override fun run() {
            if (store.version != drawnVersion) {
                drawnVersion = store.version
                refresh()
            }
            main.postDelayed(this, FRAME_MS)
        }
    }

    private fun refresh() {
        sampleLink()
        panorama.selected = selected
        panorama.dbMin = prefs.dbMin.toDouble()
        panorama.dbMax = prefs.dbMax.toDouble()
        panorama.showMaxHold = prefs.showMaxHold
        panorama.showAverage = prefs.showAverage
        panorama.invalidate()

        val now = store.now()
        val planned = store.profile != null
        tileGrid.visibility = if (planned) View.VISIBLE else View.GONE
        if (planned) tiles.forEachIndexed { id, view -> view.bind(channelTile(store, id, selected), now) }

        val deviceGain = store.status?.gain
        // The manual-gain ceiling is the PHY table depth the board reports, not our guess.
        store.status?.gainMax?.takeIf { it > 0 }?.let { if (gainBar.max != it) gainBar.max = it }
        if (!draggingGain) {
            gainBar.progress = deviceGain ?: prefs.gain
            gainValue.text = gainBar.progress.toString()
        }
        tint(maxChip, prefs.showMaxHold)
        tint(avgChip, prefs.showAverage)
        val band = currentBand()
        bandChips.forEach { (letter, chip) -> tint(chip, letter == band) }

        val live = link != null || sim != null
        val running = sim?.running ?: scanRunning()
        scanButton.setText(if (live && running) R.string.scan_stop else R.string.scan_start)
        scanButton.isEnabled = live
        gainBar.isEnabled = live
        setupButton.isEnabled = live
        sheet?.update(store.status)
        zoom?.update()
        history?.update()
        telemetry?.update(linkCounters())
        console?.update()
        gpio?.update()
        statusLine.text = status()
    }

    private fun scanRunning(): Boolean = store.telemetry?.running ?: (store.status?.running ?: 1) != 0

    private fun status(): String {
        val parts = mutableListOf<String>()
        if (note.isNotEmpty()) parts.add(note)
        val l = link
        when {
            l != null -> parts.add(l.description)
            sim != null -> parts.add(getString(R.string.status_sim))
            else -> return note.ifEmpty { getString(R.string.plug_in) }
        }
        parts.add(store.profile?.name ?: getString(R.string.status_connecting))
        store.telemetry?.let { parts.add(getString(R.string.status_rot, it.rotationHz)) }
        store.status?.let { parts.add(getString(R.string.status_hops, it.plan.size)) }
        parts.add(getString(R.string.status_gain, store.status?.gain ?: prefs.gain))
        store.telemetry?.let { parts.add(getString(R.string.status_link, it.linkFill * 100)) }
        return parts.joinToString(" · ")
    }

    /** The band the device's current plan belongs to, for the chip highlight. */
    private fun currentBand(): String? =
        planNames().firstOrNull()?.take(1)?.let { letter -> Bands.ALL.find { it.letter == letter }?.letter }

    // ---- controls ----

    /** The plan the board dials now, falling back to what we would send it. */
    private fun planNames(): List<String> =
        store.profile?.channels?.map { it.name }.orEmpty().ifEmpty { prefs.channels }

    /**
     * A new plan is measured whole: CHANNELS switches every channel of the plan it
     * builds back on, and the old mask picked channels by position in the old plan.
     */
    private fun applyPlan(names: List<String>) {
        prefs.setChannels(names)
        prefs.enabledMask = (1 shl prefs.channels.size) - 1
        note = ""
        val settings = prefs.deviceSettings()
        command(Bands.channelsCommand(settings.channels))
        command(DeviceCommands.enable(settings.enabledFor()))
        command("PROFILE?")
        command("STATUS?")
        refresh()
    }

    private fun selectBand(letter: String) = applyPlan(Bands.names(letter))

    private fun applyGain(value: Int) {
        prefs.setGain(value, gainBar.max)
        command(DeviceCommands.gain(GainMode.MANUAL, value, gainBar.max))
        command("STATUS?")
        refresh()
    }

    /** The sweep parameters only mean something against a board that can answer for them. */
    private fun openSetup() {
        if (link == null && sim == null) {
            note = getString(R.string.plug_in)
            refresh()
            return
        }
        sheet?.show(store.status)
    }

    /** A tile opens that channel's chart, and its stepper moves the rest of the screen with it. */
    private fun openZoom(id: Int) {
        if (store.profile?.channels?.getOrNull(id) == null) return
        zoom?.dismiss()
        zoom = ZoomSheet(this, prefs, store) { picked ->
            selected = picked
            refresh()
        }.also { it.show(id) }
    }

    /** The whole plan across time, which is the one view the panorama cannot give. */
    private fun openHistory() {
        history?.dismiss()
        history = HistorySheet(this, prefs, store).also { it.show(selected) }
    }

    private fun openMore() {
        val menu = PopupMenu(this, moreChip)
        menu.menu.add(0, MENU_SCALE, 0, R.string.auto_scale)
        menu.menu.add(0, MENU_HISTORY, 0, R.string.history_title)
        menu.menu.add(0, MENU_TELEMETRY, 0, R.string.telemetry_title)
        menu.menu.add(0, MENU_CONSOLE, 0, R.string.console_title)
        menu.menu.add(0, MENU_GPIO, 0, R.string.gpio_title)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SCALE -> panorama.autoScale()
                MENU_HISTORY -> openHistory()
                MENU_TELEMETRY -> openTelemetry()
                MENU_CONSOLE -> openConsole()
                MENU_GPIO -> openGpio()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        menu.show()
    }

    /** The wire in both directions, which is the only way to see what the board really said. */
    private fun openConsole() {
        console?.dismiss()
        console = ConsoleSheet(this, store, ::applySetting).also { it.show() }
    }

    /** The numbers the board reports about itself, which the instrument layout has no room for. */
    private fun openTelemetry() {
        telemetry?.dismiss()
        telemetry = TelemetrySheet(this, store).also { it.show() }
    }

    /** Which pins the board drives at start-up — only a board can say. */
    private fun openGpio() {
        if (link == null && sim == null) {
            note = getString(R.string.plug_in)
            refresh()
            return
        }
        gpio?.dismiss()
        gpio = GpioSheet(this, store, ::applyGpio).also { it.show() }
    }

    /** KiB/s and dropped frames are rates off counters, so they need sampling between the panels. */
    private fun sampleLink() {
        val now = store.now()
        parser?.let { linkRate.sample(now, it.bytes) }
        store.telemetry?.let { dropWindow.add(now, it.drops) }
    }

    private fun linkCounters(): LinkCounters {
        val p = parser ?: return LinkCounters.STANDBY
        return LinkCounters(
            live = true,
            bytes = p.bytes,
            bytesPerSecond = linkRate.perSecond,
            crcErrors = p.crcErrors,
            resyncBytes = p.resyncBytes,
            recentDrops = dropWindow.recent(store.telemetry?.drops ?: 0),
        )
    }

    /** One write, then the reply that lets the form show the device's state instead of our request. */
    private fun applySetting(line: String) {
        command(line)
        // A query already answers for itself; only a change needs the status read back.
        val answers = line.endsWith("?") || line == DeviceCommands.REBOOT || line == DeviceCommands.BOOTLOADER
        if (!answers) command("STATUS?")
        refresh()
    }

    /** The GPIO sheet reads its own state back, so a write needs no STATUS? alongside it. */
    private fun applyGpio(line: String) {
        command(line)
        refresh()
    }

    private fun toggleScan() {
        command(if (scanRunning()) "SCAN STOP" else "SCAN START")
        command("STATUS?")
        refresh()
    }

    // ---- link ----

    private fun usbManager() = getSystemService(UsbManager::class.java)!!

    private fun requestAndOpen(device: UsbDevice) {
        if (link != null) return
        UsbAccess.withPermission(this, device, ::openLink) { note = it }
    }

    private fun openLink(device: UsbDevice) {
        if (link != null) return
        val layout: CdcLayout = cdcLayoutOf(device)
            ?: run {
                note = getString(R.string.note_no_cdc, device.deviceName)
                return
            }
        val opened = runCatching { UsbSerialLink.open(this, device, layout) }
            .onFailure { note = getString(R.string.note_open_failed, it.message.orEmpty()) }
            .getOrNull() ?: return
        link = opened
        parser = StreamParser()
        runner = CommandRunner(write = { opened.write(it) }, scheduler = scheduler)
        opened.onBytes = ::onBytes
        opened.onClose = { why -> main.post { closeLink(why) } }
        lastFrameMs = SystemClock.elapsedRealtime()
        note = ""
        store.addLog(LogDir.INFO, "connected via usb")
        // The firmware boots silent in RotorHazard mode; ask for our stream first.
        command("PROTOCOL MONITOR") { handshake() }
    }

    /** Put the board in the stored state, then run the monitor scan. */
    private fun handshake() {
        command("SCAN STOP")
        prefs.deviceSettings().restoreCommands().forEach { command(it) }
        // The C5 monitor-mode spectrum: 4 hops per frame, 256 bins across 40 MHz.
        command("SPEC 4 256 40000000 MAX")
        command("PROFILE?")
        command("STATUS?")
        command("SPURS?")
        command("SCAN START")
        command("INFO")
    }

    /** Bytes arrive on the reader thread; every state change is marshalled to the UI. */
    private fun onBytes(chunk: ByteArray) {
        val items = parser?.push(chunk) ?: return
        if (items.isEmpty()) return
        val sawFrame = items.any { it is StreamItem.Frame }
        main.post {
            if (sawFrame) lastFrameMs = SystemClock.elapsedRealtime()
            for (item in items) {
                runner?.onItem(item)
                store.ingest(item)
            }
        }
    }

    private fun closeLink(why: String) {
        val l = link ?: return
        link = null
        parser = null
        runner?.failAll("link closed")
        runner = null
        l.onBytes = null
        l.onClose = null
        l.close()
        note = getString(R.string.note_disconnected, why)
        store.addLog(LogDir.INFO, "connection closed: $why")
    }

    private fun command(line: String, reply: (CommandResult) -> Unit = {}) {
        store.addLog(LogDir.TX, line)
        sim?.let {
            it.onCommand(line)
            return
        }
        val r = runner ?: run {
            store.addLog(LogDir.ERROR, "not connected: $line")
            note = getString(R.string.note_not_connected, line)
            return
        }
        r.send(line) { result ->
            if (result is CommandResult.Failed) note = result.why
            reply(result)
        }
    }

    /** Telemetry arrives at 4 Hz even when stopped, so a quiet link means the board restarted. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (link != null && SystemClock.elapsedRealtime() - lastFrameMs > NO_DATA_MS) {
                lastFrameMs = SystemClock.elapsedRealtime()
                note = getString(R.string.note_no_data)
                command("PROTOCOL MONITOR") { handshake() }
            }
            main.postDelayed(this, WATCHDOG_PERIOD_MS)
        }
    }

    /** A band or trace toggle: single letters get a fixed square so the row reads as a keypad. */
    private fun chip(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 11f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        // Tight enough that the English words still fit beside the six band letters.
        setPadding(theme.dp(3f).roundToInt(), 0, theme.dp(3f).roundToInt(), 0)
        layoutParams = LinearLayout.LayoutParams(
            if (label.length == 1) theme.dp(40f).roundToInt() else LinearLayout.LayoutParams.WRAP_CONTENT,
            theme.dp(34f).roundToInt(),
        ).apply { marginEnd = theme.dp(2f).roundToInt() }
    }

    private fun tint(button: Button, on: Boolean) = button.setTextColor(if (on) theme.accent else theme.muted)

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(theme.dp(10f).roundToInt(), 1)
    }

    companion object {
        private const val FRAME_MS = 33L
        private const val WATCHDOG_PERIOD_MS = 1000L
        private const val NO_DATA_MS = 3000L
        private const val MENU_SCALE = 1
        private const val MENU_HISTORY = 2
        private const val MENU_TELEMETRY = 3
        private const val MENU_CONSOLE = 4
        private const val MENU_GPIO = 5
        private val TILE_IDS = intArrayOf(
            R.id.tile0, R.id.tile1, R.id.tile2, R.id.tile3, R.id.tile4, R.id.tile5, R.id.tile6, R.id.tile7,
        )
    }
}
