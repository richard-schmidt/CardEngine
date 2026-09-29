#!/data/data/com.termux/files/usr/bin/env python3
"""CardEngine as an MCP surface (phase 1).

A stdio MCP server exposing the headless play loop. Deliberately thin: every
decision lives in `ccgui.AgentPlay`, where test.sh covers it, and this file only
speaks the protocol and shells out.

WHY IT CAN BE STATELESS. A game in progress is fully described by
(bundle, seed, deck picks, answers) and replays exactly, so a session
is a STRING that each call carries in and hands back. No server holds a game, no
coroutine is parked across a call, and an interrupted game is not lost -- it is
whatever session string you still have. That is the whole reason this shape
works over a request/response protocol at all.
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CGE = os.path.join(HERE, "cge")
POOL = os.path.join(HERE, "pool")


def _run(binary, *args):
    """One CLI call. Errors come back as data, never as a crash: a tool that
    dies takes the conversation's turn with it, while a tool that reports
    'no such option' can be answered."""
    try:
        r = subprocess.run([binary, *[str(a) for a in args]],
                           capture_output=True, text=True, timeout=120)
    except FileNotFoundError:
        return {"ok": False, "error": f"{binary} not built -- run agent/build-agent.sh"}
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "the engine did not finish in 120s"}
    if r.returncode != 0:
        return {"ok": False, "error": (r.stderr or r.stdout)[-600:]}
    try:
        return json.loads(r.stdout)
    except json.JSONDecodeError:
        return {"ok": False, "error": f"unreadable engine output: {r.stdout[:300]}"}


def cge(*args):
    return _run(CGE, *args)


def pool(*args):
    """The pool instrument. A SEPARATE binary on purpose -- it renders
    `ccgui.sections()`, the same list the Creator's Pool socket draws, so the
    JSON here and the screen on the phone cannot drift apart."""
    return _run(POOL, *args)


def render(v):
    """A position as text an agent can actually reason over.

    JSON is what the CLI speaks; prose is what a model plays from. Both are
    returned -- the text to read, the session to carry."""
    if not v.get("ok"):
        return "ERROR: " + str(v.get("error"))
    out = []
    if v["over"]:
        out.append(f"GAME OVER after turn {v['turn']}."
                   + (f" Lost: {', '.join(v['losers'])}." if v["losers"] else ""))
    else:
        out.append(f"Turn {v['turn']} · {v['phase']} · {v['player']} to decide")
        out.append(f"ASKED: {v['question']}")
    for s in v["seats"]:
        bits = [f"lib {s['library']}", f"grave {s['graveyard']}"]
        bits += [f"{k} {n}" for k, n in s["counters"].items()]
        bits += [f"{k} {n}" for k, n in s["pool"].items()]
        out.append(f"\n[{s['id']}] " + " · ".join(bits))
        for b in s["board"]:
            out.append(f"    {b}")
        if s["hand"]:
            out.append("    hand: " + ", ".join(s["hand"]))
    if v["since"]:
        out.append("\nSince your last decision:")
        out += [f"    {l}" for l in v["since"][-14:]]
    if v["options"]:
        out.append("\nOPTIONS (answer with the id):")
        for o in v["options"]:
            out.append(f"    {o['id']:<10} {o['label']}")
    out.append(f"\nsession: {v['session']}")
    return "\n".join(out)


TOOLS = [
    {
        "name": "list_games",
        "description": (
            "List playable games: the two bundled ones plus anything exported into ~/cge-test. "
            "Forks are NOT listed here -- use list_forks for those. Every other tool still accepts "
            "a fork id."
        ),
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "new_game",
        "description": "Deal a new game and return the opening position. Returns a session string; pass it to every later call. Same seed + same answers always replays the same game.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "game": {"type": "string", "description": "game id from list_games (default 'core')"},
                "p0deck": {"type": "integer", "description": "deck index for P0 (default 0)"},
                "p1deck": {"type": "integer", "description": "deck index for P1 (default 0)"},
                "seed": {"type": "integer", "description": "omit for the default, repeatable seed"},
            },
        },
    },
    {
        "name": "state",
        "description": "Re-read a position without changing it: whose decision, the board, both hands, what happened since the last decision, and the legal options.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "game": {"type": "string"},
                "session": {"type": "string"},
                "as_seat": {
                    "type": "string",
                    "description": "Report the position as this seat sees it ('P0'/'P1'), hiding the other hand. OMIT when one agent plays both seats -- it needs both. REQUIRED when playing against a person, or you are looking at their hand.",
                },
            },
            "required": ["session"],
        },
    },
    {
        "name": "act",
        "description": "Answer the current decision with an option id from the options list, and return the NEXT position. Only ids that were offered are accepted -- an unoffered id is refused rather than guessed at.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "game": {"type": "string"},
                "session": {"type": "string"},
                "option": {"type": "string", "description": "an id from the OPTIONS list, e.g. 'a0' or 'pass'"},
            },
            "required": ["session", "option"],
        },
    },
    {
        "name": "fork_game",
        "description": (
            "Derive a variant of a game to iterate on, WITHOUT touching the original. "
            "Use this before changing anything: an agent never edits a game, it forks it. "
            "The fork gets its own id and its own file under ~/cge-forks, so the user's games "
            "and the test corpus are never written to. Card ids are preserved (and minted if the "
            "source had none), so measurements taken on a fork can be joined to its parent card "
            "by card. Give a note saying what you intend to vary -- it is stored with the fork "
            "and is what makes a directory of variants readable later."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "game": {"type": "string", "description": "game id from list_games (default 'core'); may itself be a fork"},
                "note": {"type": "string", "description": "what is being varied, e.g. 'TurnMode.PER_PLAYER' or 'Harrow -1 cost'"},
            },
            "required": ["note"],
        },
    },
    {
        "name": "list_forks",
        "description": (
            "List your forks: id, name, what each was forked from, and the note saying what it varies. "
            "Forks are listed separately from games on purpose -- they are working material and there "
            "will be many. Use this to find a fork to measure, vary further, or delete."
        ),
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "delete_fork",
        "description": (
            "Delete one fork, permanently. Only forks can be deleted -- a real game or an export is "
            "refused. Nothing prunes forks automatically, so this is the only way they go away: say so "
            "before deleting one the user may still want a measurement from."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"game": {"type": "string", "description": "the fork id, e.g. 'fork:core-1a2b3c4d'"}},
            "required": ["game"],
        },
    },
    {
        "name": "vary_game",
        "description": (
            "Apply ONE named change to a fork. Only a fork can be varied -- fork_game first. "
            "Named variations rather than free-form field writes, on purpose: each one refuses "
            "what it cannot express (an unknown turn mode, a stat field the card's types never "
            "declared, a cost on a card that does not exist), because every one of those would "
            "otherwise produce a game that still loads and measures WRONG. "
            "Returns what changed, before and after -- and refuses a no-op, so an edit that "
            "changed nothing can never read as success. "
            "Variations: turn_mode <SHARED|PER_PLAYER>; attack_delay <true|false>; "
            "card_cost <card> <resource>=<n>[,...] (use 'any' for the generic resource); "
            "card_field <card> <field> <n>; "
            "ability_cost <card> <index> <resource>=<n> (an ACTIVATED ability's price -- paid every "
            "turn, not once, and 'exhaust' alone is not a price); "
            "enters_with <card> <counter> <n> (a Station's starting hull or store). "
            "Cards may be named by stable id or exact name."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "game": {"type": "string", "description": "the fork id to vary, e.g. 'fork:core-1a2b3c4d'"},
                "variation": {
                    "type": "string",
                    "enum": ["turn_mode", "attack_delay", "card_cost", "card_field",
                             "ability_cost", "enters_with"],
                    "description": "which named variation to apply",
                },
                "args": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "the variation's arguments, in order (see the description)",
                },
            },
            "required": ["game", "variation", "args"],
        },
    },
    {
        "name": "pool",
        "description": (
            "Measure a card pool: crowded slots (cards of the same type and cost that are hard "
            "to tell apart), cards carrying no distinguishing term, cards in no deck, the cost "
            "curve by deck role, rules-text density, stats-per-mana, and which costs and keywords "
            "the pool actually uses. Reports the shape of the set; it does NOT judge balance -- "
            "win rates need games, which is `agent/balance` at the shell. Accepts a fork id, so "
            "the authoring loop is fork_game -> vary_game -> pool."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"game": {"type": "string"}},
        },
    },
    {
        "name": "list_cards",
        "description": "The game's whole card pool with costs, stats and compiled rules text -- what each card ACTUALLY does, not its authored blurb. Read this to play well or to reason about design.",
        "inputSchema": {
            "type": "object",
            "properties": {"game": {"type": "string"}},
        },
    },
]


def handle(req):
    m = req.get("method")
    if m == "initialize":
        return {
            "protocolVersion": "2024-11-05",
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "cardengine", "version": "0.1.0"},
        }
    if m == "tools/list":
        return {"tools": TOOLS}
    if m == "tools/call":
        p = req.get("params", {})
        name = p.get("name")
        a = p.get("arguments") or {}
        game = a.get("game", "core")
        if name == "list_games":
            v = cge("games")
            if not v.get("ok"):
                text = "ERROR: " + str(v.get("error"))
            else:
                text = "\n".join(
                    f"{g['id']:<24} {g['name']}  decks: " + ", ".join(
                        f"{i}={d}" for i, d in enumerate(g["decks"]))
                    for g in v["games"])
        elif name == "new_game":
            text = render(cge("new", game, a.get("p0deck", 0), a.get("p1deck", 0),
                              a.get("seed", "")) if a.get("seed") is not None
                          else cge("new", game, a.get("p0deck", 0), a.get("p1deck", 0)))
        elif name == "state":
            text = render(cge("state", game, a["session"], a.get("as_seat", "")))
        elif name == "act":
            text = render(cge("act", game, a["session"], a["option"]))
        elif name == "fork_game":
            v = cge("fork", game, a["note"])
            if not v.get("ok"):
                text = "ERROR: " + str(v.get("error"))
            else:
                text = (
                    f"forked {game} -> {v['fork']}\n"
                    f"  note:        {v['note']}\n"
                    f"  forked from: {v['forkedFrom']}\n"
                    f"  file:        {v['path']}\n"
                    "Use that fork id with the other tools. The original is untouched."
                )
        elif name == "list_forks":
            v = cge("forks")
            if not v.get("ok"):
                text = "ERROR: " + str(v.get("error"))
            elif not v["forks"]:
                text = "no forks yet -- fork_game makes one"
            else:
                text = "\n".join(
                    f"{f['id']:<28} {f['name']}\n"
                    f"{'':<28} from {f['forkedFrom']}  |  varies: {f['note']}"
                    for f in v["forks"])
        elif name == "delete_fork":
            v = cge("delete-fork", game)
            text = ("refused: " + str(v.get("error"))) if not v.get("ok") else f"deleted {v['deleted']}"
        elif name == "vary_game":
            v = cge("vary", game, a["variation"], *[str(x) for x in a.get("args", [])])
            if not v.get("ok"):
                # A refusal is INFORMATION, not a crash -- it names what the
                # schema will accept, so say so plainly rather than as an error.
                text = "refused: " + str(v.get("error"))
            else:
                text = (
                    f"varied {v['fork']}\n"
                    f"  {v['varied']}: {v['before']}  ->  {v['after']}\n"
                    "The fork is saved. Measure it with the balance tool, or play it."
                )
        elif name == "pool":
            v = pool(game, "--json")
            if not v.get("ok"):
                text = "ERROR: " + str(v.get("error"))
            else:
                lines = [f"POOL -- {game}  ({v['cards']} cards)"]
                for s in v["sections"]:
                    lines.append("")
                    lines.append(f"== {s['title']} ==")
                    # The note carries the caveat -- several of these measures
                    # are easy to misread, so it is not trimmed for brevity.
                    lines.append(f"   {s['note']}")
                    if not s["lines"]:
                        lines.append("   (none)")
                    for l in s["lines"]:
                        lines.append(f"   {l['text']}")
                text = "\n".join(lines)
        elif name == "list_cards":
            v = cge("cards", game)
            if not v.get("ok"):
                text = "ERROR: " + str(v.get("error"))
            else:
                lines = []
                for c in v["cards"]:
                    stats = " ".join(f"{k} {n}" for k, n in c["fields"].items())
                    lines.append(f"{c['name']}  [{' '.join(c['types'])}]  cost {c['cost']}  {stats}")
                    lines += [f"    · {r}" for r in c["rules"]]
                text = "\n".join(lines)
        else:
            return {"error": {"code": -32601, "message": f"no such tool: {name}"}}
        return {"content": [{"type": "text", "text": text}]}
    return {"error": {"code": -32601, "message": f"unknown method: {m}"}}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            continue
        result = handle(req)
        # A notification (no id) expects no reply -- answering one is a protocol
        # error, not a harmless extra.
        if req.get("id") is None:
            continue
        out = {"jsonrpc": "2.0", "id": req["id"]}
        if isinstance(result, dict) and "error" in result and "content" not in result:
            out["error"] = result["error"]
        else:
            out["result"] = result
        sys.stdout.write(json.dumps(out) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
