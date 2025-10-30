package com.dom.samplenavigation.base

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.CallSuper
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// BaseActivity is a class that extends AppCompatActivity and is used as a base class for all activities.
abstract class BaseActivity<T : ViewDataBinding>(
    @LayoutRes private val layoutRes: Int
) : AppCompatActivity() {

    val keyBoardShowingLiveData: MutableLiveData<Boolean> = MutableLiveData(false)

    lateinit var binding: T // 데이터 바인딩

    var hasNewOrder: Boolean = false   // 신규주문 알림 구분자

    val TAG: String = this.javaClass.simpleName

    private val subscriptionTopicDev = "driverTest"
    private val subscriptionTopicProd = "driver"

    // ---- Orientation policy hooks (DRY across Activities) ----
    // 기본 정책:
    //  - 폰(sw < 600dp): 세로 선호(고정)
    //  - 대형 화면(sw ≥ 600dp): 가변(시스템에 위임)
    // 필요 시 서브클래스에서 override 하여 화면별로 조정
    protected open fun preferPortraitOnPhones(): Boolean = true
    protected open fun isLargeScreen(): Boolean =
        resources.configuration.smallestScreenWidthDp >= 600

    protected open fun forceUnspecifiedOrientation(): Boolean = false

    //    lateinit var timer: TimeCheckService
    var viewNewOrderAlert: View? = null
        set(value) {
            // 신규 주문 알림 - 상태바 높이 지정
            setNewOrderAlertHeight(value!!)
            field = value
        }

    fun applySystemBarMargins(
        targetView: View,
        applyTop: Boolean = true,
        applyBottom: Boolean = false
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(targetView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams

            if (params != null) {
                if (applyTop) {
                    params.topMargin = systemBars.top
                }
                if (applyBottom) {
                    params.bottomMargin = systemBars.bottom
                }
                view.layoutParams = params
            }
            insets
        }
    }

    fun setupKeyboardListener(decorView: View, scrollTargetView: View) {
        ViewCompat.setOnApplyWindowInsetsListener(decorView) { _, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val sysBarHeight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom

            val dy = if (imeHeight > 0) -(imeHeight - sysBarHeight).toFloat() else 0f
            scrollTargetView.translationY = dy

            insets
        }
    }

    private fun applyPreferredOrientation() {
        if (forceUnspecifiedOrientation()) {
            // 특정 화면에서 완전 자유 회전을 원할 때
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            return
        }
        requestedOrientation = if (isLargeScreen()) {
            // 태블릿/폴더블: 시스템에 위임 (적응형 레이아웃)
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            // 폰: 기본은 세로 선호
            if (preferPortraitOnPhones()) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 가능한 한 이른 타이밍에 방향 결정을 내려 중복 inflate/깜빡임 최소화
        applyPreferredOrientation()
        // layoutResId로 DataBinding 객체 생성
        binding = DataBindingUtil.setContentView(this, layoutRes)
        // lifecycleOwner 지정
        binding.lifecycleOwner = this@BaseActivity

        // enableEdgeToEdge를 DataBinding 설정 후에 호출
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.WHITE, Color.WHITE),
            navigationBarStyle = SystemBarStyle.light(
                Color.argb(0xe6, 0xFF, 0xFF, 0xFF),
                Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
            )
        )


        // 네비게이션 바 마진을 WindowInsets로 자동 처리
        if (!this.isGestureNavigation()) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
                val navigationBarHeight =
                    insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                if (navigationBarHeight > 0) {
                    val params = view.layoutParams as? ViewGroup.MarginLayoutParams
                    if (params != null && params.bottomMargin != navigationBarHeight) {
                        params.bottomMargin = navigationBarHeight
                        view.layoutParams = params
                    }
                }
                insets
            }
        }

//        timer = TimeCheckService(this, viewModel.countTime,1000)
//        setMarketInfo()
//        setDisplayBind()
//        setRv()
        setPermission()
    }

    protected fun binding(action: T.() -> Unit) {
        binding.run(action)
    }

    override fun onRestart() {
        super.onRestart()
        Log.i(TAG, "onRestart")
    }


    override fun onStart() {
        super.onStart()
        Log.i(TAG, "onStart")
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause")
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
    }

//    fun startTimer(){
//        if (!timer.isRunning){
//            timer.isRunning = true
//            timer.start()
//        }
//    }
//
//    fun stopTimer(){
//        if (timer.isRunning){
//            timer.isRunning = false
//            timer.time = 1
//            timer.cancel()
//        }
//    }
//
//    fun resetTimer(){
//        stopTimer()
//        startTimer()
//    }
//
//
//    override fun onTouchEvent(event: MotionEvent?): Boolean {
//        when (event?.action) {
//            MotionEvent.ACTION_DOWN -> if (timer.isRunning){
//                println("다운")
//            }
//            MotionEvent.ACTION_UP -> if (timer.isRunning){
//                println("업")
//            }
//            MotionEvent.ACTION_MOVE -> if (timer.isRunning){
//                println("무브")
//            }
//        }
//
//        return super.onTouchEvent(event)
//    }
//
//    /**
//     * 리사이클러뷰를 셋팅
//     */
//    @SuppressLint("UseCompatLoadingForDrawables")
//    open fun setRv(){
//
//    }


    //region 권한

    val PERMISSION_FORE_LOC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION, // 대략적 위치 액세스
            Manifest.permission.ACCESS_FINE_LOCATION,   // 정확한 위치 액세스
            Manifest.permission.POST_NOTIFICATIONS      // 알림 권한
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION, // 대략적 위치 액세스
            Manifest.permission.ACCESS_FINE_LOCATION,   // 정확한 위치 액세스
        )
    }


    val permissionForCamera = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.CAMERA
        )
    } else {
        arrayOf(
            Manifest.permission.CAMERA,                 // 카메라
            Manifest.permission.READ_EXTERNAL_STORAGE,  // 파일 읽기
//            Manifest.permission.WRITE_EXTERNAL_STORAGE,  // 파일 쓰기
        )
    }


//    public static String[] PERMISSION_BACK_LOC = new String[]{
//            Manifest.permission.ACCESS_BACKGROUND_LOCATION          // sdk 29 이상에서 지원
//    };

    lateinit var reqForeLocPermission: ActivityResultLauncher<Array<String>>
    lateinit var reqForeCamPermission: ActivityResultLauncher<Array<String>>

    private fun setPermission() {
        reqForeLocPermission = this.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val fineLocationGranted: Boolean =
                result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)
            val coarseLocationGranted: Boolean =
                result.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)

            if (fineLocationGranted && coarseLocationGranted) {
                // location access granted.
                showToast("권한이 허용되었습니다.")
            } else {
                // No location access granted.
                showToast("권한 허용이 필요합니다.")
            }


        }

        reqForeCamPermission = this.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val cameraGranted: Boolean =
                result.getOrDefault(Manifest.permission.CAMERA, false)
            val readExGranted: Boolean =
                result.getOrDefault(Manifest.permission.READ_EXTERNAL_STORAGE, false)
//            val writeExGranted: Boolean =
//                result.getOrDefault(Manifest.permission.WRITE_EXTERNAL_STORAGE, false)

            if (!cameraGranted || !readExGranted /*|| !writeExGranted*/) {
                // No Cam, Repository access granted.
                showToast("카메라 및 파일 권한 허용이 필요합니다.")
            }
        }

//        // api version 29부터 지원하기 때문에 버전 분기처리 없어도 됌 (Q 미만 버전 지원 시 분기처리 필요해서 넣어둠)
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//            reqBackLocPermission = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
//                        Boolean backLocationGranted = result.getOrDefault(Manifest.permission.ACCESS_BACKGROUND_LOCATION, false);
//
//                        if (backLocationGranted != null && backLocationGranted) {
//                            // location access granted.
//                            Toast.makeText(this, "백그라운드 위치 액세스 권한 허용", Toast.LENGTH_SHORT).show();
//                        } else {
//                            // No location access granted.
//                            Toast.makeText(this, "백그라운드 위치 액세스 권한 거부", Toast.LENGTH_SHORT).show();
//                        }
//                    }
//            );
//        }
    }

    open fun checkPermission(permission: Array<String>): Boolean {
        for (value: String in permission) {
            if (ActivityCompat.checkSelfPermission(
                    this@BaseActivity,
                    permission[0]
                ) == PackageManager.PERMISSION_DENIED
            ) {
                return false
            }
        }

        return true
    }


    /**
     * 위치 권한 요청 거부 시 설정 화면으로 이동 메소드
     */
//    open fun openSettings(){
//        showToast("권한 허용을 위해 설정화면으로 이동합니다.")
//        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
//            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//            Intent.setData = Uri.fromParts("package", packageName, null)
//        }.run(:: startActivity)
//    }

    /**
     * 위치 권한 요청 메소드
     * @param permission 위치권한 요청 Array
     */
    open fun requestPermission(permission: Array<String>) {
        // 권한요청
        reqForeLocPermission.launch(permission)
    }

    /**
     * 카메라 및 파일 접근 권한 요청메소드
     * @param permission 카메라 및 파일접근 권한 Array
     */
    open fun requestCamPermission(permission: Array<String>) {
        reqForeCamPermission.launch(permission)
    }
    //endregion


    /**
     * 신규 주문 알림 - 상태바
     * 상태바 높이 지정
     * @param view 상태바 View
     */
    private fun setNewOrderAlertHeight(view: View) {
        // Use WindowInsets instead of internal "status_bar_height" resource.
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            // Ensure we have layout params; fall back to match_parent width if missing.
            val lp = (v.layoutParams
                ?: LinearLayoutCompat.LayoutParams(
                    LinearLayoutCompat.LayoutParams.MATCH_PARENT,
                    0
                ))
            if (lp.height != topInset) {
                lp.height = topInset
                v.layoutParams = lp
            }
            insets
        }
        // Trigger an inset pass immediately.
        view.requestApplyInsets()
    }

    // Broadcast Receiver 전송
    fun sendBR(action: String) {
        val intent = Intent(action)
        intent.setPackage("com.dit.action")
        sendBroadcast(intent)
    }


    /**
     * resource String 반환 메소드
     * @param resources 리소스 값
     * @return String.xml의 정의돈 String 값
     */
    fun getResourcesString(resources: Int): String {
        return ContextCompat.getString(application.applicationContext, resources)
    }


    /**
     * resource Integer 반환 메소드
     * @param resources 리소스 값
     * @return String.xml의 정의된 Integer 값
     */
    open fun getResourcesInteger(resources: Int): Int {
        return application.applicationContext.resources.getInteger(resources)
    }

    /**
     * resource Color 반환 메소드
     * @param resources 리소스 값
     * @return color.xml의 정의된 Color 값
     */
    open fun getResourcesColor(resources: Int): Int {
        return ContextCompat.getColor(applicationContext, resources)
    }

    open fun showToast(message: String) {
        this@BaseActivity.lifecycle.coroutineScope.launch {
            withContext(Dispatchers.Main) {
                try {
                    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "showToast Error: $e")
                }
            }
        }
    }

    /**
     * 네트워크 상태 체크 메소드
     */
//    open fun isNetworkConnected(){
//        // 네트워크 체크 객체 생성
//        val netUtil = NetworkUtil(applicationContext)
//        if(!netUtil.isConnected(this)){
//            showToast("네트워크 연결을 확인해 주세요.")
//        }
//    }

    // 네비게이션 바 제스처 네비게이션 여부 확인
    private fun AppCompatActivity.isGestureNavigation(): Boolean {
        val root = window?.decorView ?: return false
        val insets = ViewCompat.getRootWindowInsets(root) ?: return false
        val navBarsVisible = insets.isVisible(WindowInsetsCompat.Type.navigationBars())
        val gestureInsets = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
        return !navBarsVisible && (gestureInsets.bottom > 0 || gestureInsets.left > 0 || gestureInsets.right > 0)
    }

    // 내비게이션 바 높이
    private fun getNavigationBarHeight(): Int {
        val root = window?.decorView ?: return 0
        val insets = ViewCompat.getRootWindowInsets(root) ?: return 0
        return insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
    }


//
//    protected val fileLiveData = MutableLiveData<File?>()
//
//    protected val multiFileLiveData = MutableLiveData<Boolean>(false)
//    protected var multiFiles = ArrayList<File?>()

    /**
     * gallery
     */
//    private val multiplePhotoRequest = registerForActivityResult(
//        ActivityResultContracts.PickMultipleVisualMedia(3)
//    ) { uriList ->
//
//        if (uriList.isNullOrEmpty()) return@registerForActivityResult
//
//        multiFiles = ArrayList(uriList.map { FileUtil.uriToFile(this@BaseActivity, it, true) })
//        multiFileLiveData.value = true
//    }
//
//    private val singlePhotoRequest = registerForActivityResult(
//        ActivityResultContracts.PickVisualMedia()
//    ) { uri ->
//
//        if (uri == null) return@registerForActivityResult
//
//        val file = FileUtil.uriToFile(this@BaseActivity, uri, true)
//        fileLiveData.value = file
//
//    }


//    protected fun openGallery(isMultiple: Boolean? = true) {
//        if (isMultiple == true) {
//            multiplePhotoRequest.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
//        } else {
//            singlePhotoRequest.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
//        }
//    }
//
//
//    // check keyboard is showing or not
//    protected fun isKeyboardShowing(): Boolean {
//        val rect = Rect()
//        window.decorView.getWindowVisibleDisplayFrame(rect)
//        val screenHeight = window.decorView.height
//        val keypadHeight = screenHeight - rect.bottom
//        return keypadHeight > screenHeight * 0.15 // 15% of the screen height
//    }
//
    protected fun hideKeyboard() {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
    }

//    fun hideSoftKeyboard() {
//        if (currentFocus != null) {
//            val inputMethodManager: InputMethodManager =
//                getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
//            inputMethodManager.hideSoftInputFromWindow(currentFocus!!.windowToken, 0)
//        }
//    }
//
//    fun setKeyboardVisibilityListener() {
//        window.decorView.rootView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener{
//            var alreadyOpen = false
//            override fun onGlobalLayout() {
//                val isShown = keyboardVisible()
//                if (isShown == alreadyOpen) {
//                    return
//                }
//                alreadyOpen = isShown
//                keyBoardShowingLiveData.value = isShown
//            }
//        })
//    }
//
//    fun keyboardVisible(): Boolean {
//        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//            // Android 11 이상
//            val insets = window.decorView.rootWindowInsets
//            return insets?.isVisible(WindowInsets.Type.ime()) ?: false
//        } else {
//            val rect = Rect()
//            val rootView = window.decorView.rootView
//            rootView.getWindowVisibleDisplayFrame(rect)
//            val screenHeight = rootView.height
//            val keypadHeight = screenHeight - rect.bottom
//            return keypadHeight > screenHeight * 0.15
//        }
//    }

}