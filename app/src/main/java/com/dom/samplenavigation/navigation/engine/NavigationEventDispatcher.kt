package com.dom.samplenavigation.navigation.engine

import timber.log.Timber

/**
 * Navigation 이벤트를 전달하는 디스패처
 * MapLibre 스타일의 NavigationEventDispatcher
 */
class NavigationEventDispatcher {
    private val progressChangeListeners = mutableSetOf<ProgressChangeListener>()
    private val offRouteListeners = mutableSetOf<OffRouteListener>()

    /**
     * Progress change listener 추가
     */
    fun addProgressChangeListener(listener: ProgressChangeListener) {
        val added = progressChangeListeners.add(listener)
        if (!added) {
            Timber.w("ProgressChangeListener already added")
        }
    }

    /**
     * Progress change listener 제거
     */
    fun removeProgressChangeListener(listener: ProgressChangeListener?) {
        if (listener == null) {
            progressChangeListeners.clear()
        } else {
            progressChangeListeners.remove(listener)
        }
    }

    /**
     * Off route listener 추가
     */
    fun addOffRouteListener(listener: OffRouteListener) {
        val added = offRouteListeners.add(listener)
        if (!added) {
            Timber.w("OffRouteListener already added")
        }
    }

    /**
     * Off route listener 제거
     */
    fun removeOffRouteListener(listener: OffRouteListener?) {
        if (listener == null) {
            offRouteListeners.clear()
        } else {
            offRouteListeners.remove(listener)
        }
    }

    /**
     * Progress change 이벤트 전달
     */
    fun onProgressChange(location: Location, routeProgress: RouteProgress) {
        Timber.d("NavigationEventDispatcher.onProgressChange: listeners=${progressChangeListeners.size}, location=${location.latLng}")
        progressChangeListeners.forEach { listener ->
            try {
                listener.onProgressChange(location, routeProgress)
                Timber.d("NavigationEventDispatcher: ProgressChangeListener called successfully")
            } catch (e: Exception) {
                Timber.e(e, "Error in ProgressChangeListener")
            }
        }
    }

    /**
     * Off route 이벤트 전달
     */
    fun onUserOffRoute(location: Location) {
        offRouteListeners.forEach { listener ->
            try {
                listener.onUserOffRoute(location)
            } catch (e: Exception) {
                Timber.e(e, "Error in OffRouteListener")
            }
        }
    }
}

/**
 * Progress change 이벤트 리스너
 */
interface ProgressChangeListener {
    fun onProgressChange(location: Location, routeProgress: RouteProgress)
}

/**
 * Off route 이벤트 리스너
 */
interface OffRouteListener {
    fun onUserOffRoute(location: Location)
}

