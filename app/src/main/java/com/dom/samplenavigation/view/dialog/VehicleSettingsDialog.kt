package com.dom.samplenavigation.view.dialog

import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.dom.samplenavigation.R
import com.dom.samplenavigation.base.BaseDialogFragment
import com.dom.samplenavigation.databinding.DialogVehicleSettingBinding
import com.dom.samplenavigation.util.VehiclePreferences
import timber.log.Timber

class VehicleSettingsDialog: BaseDialogFragment<DialogVehicleSettingBinding>(R.layout.dialog_vehicle_setting) {

    private lateinit var vehiclePreferences: VehiclePreferences

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        vehiclePreferences = VehiclePreferences(requireContext())
        
        binding {
            // 저장된 값 불러오기
            val savedNavBasicId = vehiclePreferences.getNavBasicId()
            val savedCarType = vehiclePreferences.getCarType()
            
            // 초기값 설정
            tvVehicleId.setText(savedNavBasicId.toString())
            tvVehicleType.setText(savedCarType.toString())
            
            // 확인 버튼 클릭 리스너
            okButton.setOnClickListener {
                val navBasicId = tvVehicleId.text.toString().toIntOrNull() ?: 1
                val carType = tvVehicleType.text.toString().toIntOrNull()?.coerceIn(1, 3) ?: 1
                
                vehiclePreferences.saveNavBasicId(navBasicId)
                vehiclePreferences.saveCarType(carType)
                
                Toast.makeText(requireContext(), "차량 정보가 저장되었습니다", Toast.LENGTH_SHORT).show()
                Timber.d("Vehicle settings saved: navBasicId=$navBasicId, carType=$carType")
                
                dismiss()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // set dialog width to 90% of screen width
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            dialog?.window?.attributes?.height ?: 0
        )
    }
}