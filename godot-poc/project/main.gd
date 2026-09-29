extends Control

# CGE Godot PoC -- proves the EXPORT pipeline end to end, and probes the two
# ways a Godot app could reach a bundle that lives on the Kotlin side.
#
# Probe A: read the bundle straight off shared storage. Cheap, but Android's
#          scoped storage may refuse a non-media .json without a broad
#          permission. If it fails HERE, it fails for the real app too.
# Probe B: fetch the same bundle over a loopback socket from a Termux-side
#          server. This is the engine seam; it needs no storage permission.
#
# Whichever one wins, the card below is drawn from REAL EPR Skirmish data, not
# from a hard-coded fixture -- a renderer fed by a fixture proves nothing.

const BUNDLE_PATH := "/storage/emulated/0/CardEngine/godot-poc/epr-skirmish.json"
const HOST := "127.0.0.1"
const PORT := 9944

var log_box: VBoxContainer
var card_slot: HBoxContainer
var socket := StreamPeerTCP.new()
var socket_state := "idle"
var socket_buffer := PackedByteArray()
var probe_a_doc = null
var probe_b_doc = null
var drawn := false

func _ready() -> void:
	var root := VBoxContainer.new()
	root.set_anchors_preset(Control.PRESET_FULL_RECT)
	root.add_theme_constant_override("separation", 14)
	root.offset_left = 18
	root.offset_top = 18
	root.offset_right = -18
	root.offset_bottom = -18
	add_child(root)

	var title := Label.new()
	title.text = "CGE Godot export PoC"
	title.add_theme_font_size_override("font_size", 34)
	root.add_child(title)

	var sub := Label.new()
	sub.text = "Godot %s  -  renderer: %s" % [
		Engine.get_version_info().string,
		ProjectSettings.get_setting("rendering/renderer/rendering_method", "?")
	]
	sub.add_theme_color_override("font_color", Color(0.65, 0.7, 0.8))
	root.add_child(sub)

	log_box = VBoxContainer.new()
	root.add_child(log_box)

	card_slot = HBoxContainer.new()
	card_slot.add_theme_constant_override("separation", 12)
	root.add_child(card_slot)

	say("engine booted, scene tree alive", true)
	probe_storage()
	probe_socket()

func say(text: String, ok: bool) -> void:
	var l := Label.new()
	l.text = ("[ok]   " if ok else "[FAIL] ") + text
	l.add_theme_color_override("font_color",
		Color(0.45, 0.85, 0.5) if ok else Color(0.95, 0.45, 0.4))
	l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	log_box.add_child(l)

# --- Probe A: shared storage ------------------------------------------------

func probe_storage() -> void:
	if not FileAccess.file_exists(BUNDLE_PATH):
		say("probe A storage: %s not visible (err %d)" % [BUNDLE_PATH, FileAccess.get_open_error()], false)
		return
	var f := FileAccess.open(BUNDLE_PATH, FileAccess.READ)
	if f == null:
		say("probe A storage: open refused, err %d (scoped storage?)" % FileAccess.get_open_error(), false)
		return
	var raw := f.get_as_text()
	f.close()
	var parsed = JSON.parse_string(raw)
	if parsed == null:
		say("probe A storage: read %d bytes but JSON did not parse" % raw.length(), false)
		return
	probe_a_doc = parsed
	say("probe A storage: read and parsed %d bytes from shared storage" % raw.length(), true)
	try_draw()

# --- Probe B: loopback socket ----------------------------------------------

func probe_socket() -> void:
	var err := socket.connect_to_host(HOST, PORT)
	if err != OK:
		say("probe B socket: connect_to_host refused immediately (err %d)" % err, false)
		socket_state = "dead"
		return
	socket_state = "connecting"
	say("probe B socket: dialling %s:%d ..." % [HOST, PORT], true)

func _process(_delta: float) -> void:
	if socket_state in ["idle", "dead", "done"]:
		return
	socket.poll()
	var st := socket.get_status()
	if socket_state == "connecting":
		if st == StreamPeerTCP.STATUS_CONNECTED:
			socket_state = "reading"
			socket.put_data("GET bundle\n".to_utf8_buffer())
			say("probe B socket: connected, asked for the bundle", true)
		elif st == StreamPeerTCP.STATUS_ERROR:
			socket_state = "dead"
			say("probe B socket: no Termux server listening on %d" % PORT, false)
		return
	if socket_state == "reading":
		var n := socket.get_available_bytes()
		if n > 0:
			var chunk = socket.get_data(n)
			if chunk[0] == OK:
				socket_buffer.append_array(chunk[1])
		if st != StreamPeerTCP.STATUS_CONNECTED and n == 0:
			socket_state = "done"
			var raw := socket_buffer.get_string_from_utf8()
			var parsed = JSON.parse_string(raw)
			if parsed == null:
				say("probe B socket: got %d bytes, JSON did not parse" % raw.length(), false)
				return
			probe_b_doc = parsed
			say("probe B socket: received and parsed %d bytes over loopback" % raw.length(), true)
			try_draw()

# --- Draw a real card ------------------------------------------------------

func try_draw() -> void:
	if drawn:
		return
	var doc = probe_a_doc if probe_a_doc != null else probe_b_doc
	if doc == null or typeof(doc) != TYPE_DICTIONARY:
		return
	# Real EPR Skirmish shape: sets[].cards[].faces[]. A renderer written against
	# a guessed shape draws an empty box and reports success, so walk the real one.
	var cards := []
	for s in doc.get("sets", []):
		for c in s.get("cards", []):
			cards.append(c)
	if cards.is_empty():
		say("bundle '%s' carried no sets[].cards[] -- nothing to draw" % str(doc.get("name", "?")), false)
		return
	drawn = true
	say("bundle '%s': %d cards across %d set(s); drawing the first two" % [
		str(doc.get("name", "?")), cards.size(), doc.get("sets", []).size()], true)
	for i in range(min(2, cards.size())):
		card_slot.add_child(make_card(cards[i]))

func make_card(card: Dictionary) -> Control:
	var faces = card.get("faces", [])
	var face: Dictionary = faces[0] if not faces.is_empty() else {}

	var panel := PanelContainer.new()
	panel.custom_minimum_size = Vector2(230, 330)
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.13, 0.15, 0.21)
	sb.border_color = Color(0.45, 0.55, 0.75)
	sb.set_border_width_all(2)
	sb.set_corner_radius_all(14)
	sb.set_content_margin_all(12)
	panel.add_theme_stylebox_override("panel", sb)

	var col := VBoxContainer.new()
	panel.add_child(col)

	var name_label := Label.new()
	name_label.text = str(face.get("name", "(unnamed)"))
	name_label.add_theme_font_size_override("font_size", 22)
	name_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	col.add_child(name_label)

	var types := Label.new()
	var t = face.get("types", [])
	types.text = ", ".join(PackedStringArray(t)) if t is Array else str(t)
	types.add_theme_color_override("font_color", Color(0.6, 0.68, 0.8))
	col.add_child(types)

	col.add_child(HSeparator.new())

	# Counts, not rendered rules text: the Effect AST is stage 3's problem, and
	# pretending to render it here would overstate what this PoC proves.
	var facts := Label.new()
	facts.text = "triggers: %d\nactivated: %d\nstatics: %d\nid: %s" % [
		face.get("triggers", []).size(),
		face.get("activated", []).size(),
		face.get("statics", []).size(),
		str(card.get("id", "?"))
	]
	facts.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	facts.size_flags_vertical = Control.SIZE_EXPAND_FILL
	col.add_child(facts)
	return panel
