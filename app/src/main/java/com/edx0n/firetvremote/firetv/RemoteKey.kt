package com.edx0n.firetvremote.firetv

/** Como cada botão conversa com a TV — mesmas regras do app oficial. */
enum class Press {
    /** keyDown ao encostar o dedo, keyUp ao soltar (direcional). Segurando, a TV repete sozinha. */
    DOWN_UP,

    /** Toque curto = um clique ao soltar; passou de 300 ms = keyDown, e keyUp ao soltar. */
    LONG_PRESS,

    /** Um clique ao tocar. */
    TAP,
}

/**
 * Teclas do controle e o comando correspondente na API do Fire TV.
 * [media] = vai para `/v1/media?action=`, senão `/v1/FireTV?action=`; [scan] = direção do scan.
 */
enum class RemoteKey(
    val action: String,
    val press: Press,
    val media: Boolean = false,
    val scan: String? = null,
) {
    UP("dpad_up", Press.DOWN_UP),
    DOWN("dpad_down", Press.DOWN_UP),
    LEFT("dpad_left", Press.DOWN_UP),
    RIGHT("dpad_right", Press.DOWN_UP),
    SELECT("select", Press.DOWN_UP),
    BACK("back", Press.TAP),
    MENU("menu", Press.TAP),
    HOME("home", Press.LONG_PRESS),
    VOLUME_UP("volume_up", Press.LONG_PRESS),
    VOLUME_DOWN("volume_down", Press.LONG_PRESS),
    MUTE("mute", Press.TAP),
    PLAY_PAUSE("play", Press.LONG_PRESS, media = true),
    REWIND("scan", Press.LONG_PRESS, media = true, scan = "back"),
    FAST_FORWARD("scan", Press.LONG_PRESS, media = true, scan = "forward"),
    BACKSPACE("backspace", Press.TAP),
    SLEEP("sleep", Press.TAP),
    POWER("power", Press.TAP),
}
