package com.seungjae.jangsu280battery

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Keeps the RACE profile wording user-facing and simple: 이름 / 닉네임 / 배번.
 * The protocol/internal field remains `name`; only visible labels are changed.
 */
object RaceNameLabelUiInstaller {
    private val listeners = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()

    fun install(activity: Activity) {
        if (listeners.containsKey(activity)) {
            rewrite(activity.window.decorView)
            return
        }
        val root = activity.window.decorView
        val listener = ViewTreeObserver.OnGlobalLayoutListener { rewrite(root) }
        listeners[activity] = listener
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        rewrite(root)
    }

    fun uninstall(activity: Activity) {
        val listener = listeners.remove(activity) ?: return
        val root = activity.window.decorView
        if (root.viewTreeObserver.isAlive) {
            root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    private fun rewrite(view: View) {
        if (view is TextView) {
            val current = view.text?.toString().orEmpty()
            if (current.contains("아이디")) view.text = current.replace("아이디", "이름")
            val hint = view.hint?.toString().orEmpty()
            if (hint.contains("아이디")) view.hint = hint.replace("아이디", "이름")
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) rewrite(view.getChildAt(i))
        }
    }
}
