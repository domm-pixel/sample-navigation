package com.dom.samplenavigation.view

import androidx.activity.viewModels
import com.dom.samplenavigation.R
import com.dom.samplenavigation.base.BaseActivity
import com.dom.samplenavigation.databinding.ActivityNavigationBinding
import com.dom.samplenavigation.view.viewmodel.NavigationViewModel
import com.naver.maps.map.NaverMap
import com.naver.maps.map.OnMapReadyCallback
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class NavigationActivity : BaseActivity<ActivityNavigationBinding>(
    R.layout.activity_navigation
), OnMapReadyCallback {

    private val navigationViewModel: NavigationViewModel by viewModels()


    override fun onMapReady(p0: NaverMap) {

    }


}