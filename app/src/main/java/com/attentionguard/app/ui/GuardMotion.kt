package com.attentionguard.app.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator

/** One-shot relay rhythm. No timers or animation work survives detachment. */
object GuardMotion {
    fun enter(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val animation = view.animate()
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                animation.cancel()
                v.alpha = 1f
                v.translationY = 0f
                v.removeOnAttachStateChangeListener(this)
            }
        }
        view.addOnAttachStateChangeListener(listener)
        animation.setListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: android.animation.Animator) {
                view.removeOnAttachStateChangeListener(listener)
                animation.setListener(null)
            }
        })
        view.alpha = 0f
        view.translationY = 8f * view.resources.displayMetrics.density
        animation.alpha(1f).translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
    }

    fun acknowledge(view: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val animator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, .92f, 1.04f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, .92f, 1.04f, 1f)
            )
            duration = 360
            interpolator = DecelerateInterpolator()
        }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { animator.cancel(); v.removeOnAttachStateChangeListener(this) }
        }
        view.addOnAttachStateChangeListener(listener)
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                view.scaleX = 1f
                view.scaleY = 1f
                view.removeOnAttachStateChangeListener(listener)
            }
        })
        animator.start()
    }
}
