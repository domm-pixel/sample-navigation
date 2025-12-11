package com.dom.samplenavigation.base

import android.os.Bundle
import android.view.View
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.dom.samplenavigation.api.navigation.util.Event

abstract class BaseViewModel : ViewModel() {
    protected val TAG = this.javaClass.name

    private val _toolbarTitle = MutableLiveData<String>()
    val toolbarTitle: LiveData<String> = _toolbarTitle

    // todo - 에러처리 시, 데이터모델 더 고려해볼것
    // 공통 에러 처리 livedata
    val networkErrorHandler = MutableLiveData<String>()

    private val _callbackEvent = MutableLiveData<Event<Bundle?>>()
    val callbackEvent: LiveData<Event<Bundle?>> = _callbackEvent

    private val _simpleClickEvent = MutableLiveData<Event<Int>>()
    val simpleClickEvent: LiveData<Event<Int>> = _simpleClickEvent

    protected val _isLoading = MutableLiveData<Boolean>()
    val isLoading: MutableLiveData<Boolean> = _isLoading

    fun onCallbackEvent(bundle: Bundle? = null) {
        _callbackEvent.value = Event(bundle)
    }

    fun onSimpleCallBackEvent(view: View) {
        _simpleClickEvent.value = Event(view.id)
    }

    fun setToolbarTitle(title: String) {
        _toolbarTitle.value = title
    }
}