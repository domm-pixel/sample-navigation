package com.dom.samplenavigation.util

import android.content.Context
import android.content.SharedPreferences
import com.dom.samplenavigation.constant.PREF_NAME

/**
 * 차량 정보를 SharedPreferences에 저장하고 불러오는 유틸리티 클래스
 */
class VehiclePreferences(context: Context) {
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_NAV_BASIC_ID = "nav_basic_id"
        private const val KEY_CAR_TYPE = "car_type"
        
        // 기본값
        private const val DEFAULT_NAV_BASIC_ID = 1
        private const val DEFAULT_CAR_TYPE = 1
    }
    
    /**
     * navBasicId 저장
     * @param navBasicId 차량 번호
     */
    fun saveNavBasicId(navBasicId: Int) {
        sharedPreferences.edit()
            .putInt(KEY_NAV_BASIC_ID, navBasicId)
            .apply()
    }
    
    /**
     * navBasicId 불러오기
     * @return 저장된 navBasicId, 없으면 기본값 1 반환
     */
    fun getNavBasicId(): Int {
        return sharedPreferences.getInt(KEY_NAV_BASIC_ID, DEFAULT_NAV_BASIC_ID)
    }
    
    /**
     * carType 저장
     * @param carType 차량 유형 (1: 소형, 2: 중형, 3: 대형)
     */
    fun saveCarType(carType: Int) {
        sharedPreferences.edit()
            .putInt(KEY_CAR_TYPE, carType)
            .apply()
    }
    
    /**
     * carType 불러오기
     * @return 저장된 carType, 없으면 기본값 1 반환
     */
    fun getCarType(): Int {
        return sharedPreferences.getInt(KEY_CAR_TYPE, DEFAULT_CAR_TYPE)
    }
}

