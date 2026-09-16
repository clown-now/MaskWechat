package com.lu.wxmask.plugin.part

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import com.lu.lposed.api2.XC_MethodHook2
import com.lu.lposed.api2.XposedHelpers2
import com.lu.lposed.plugin.IPlugin
import com.lu.magic.util.log.LogUtil
import com.lu.wxmask.ClazzN
import com.lu.wxmask.Constrant
import com.lu.wxmask.plugin.WXMaskPlugin
import com.lu.wxmask.util.AppVersionUtil
import com.lu.wxmask.util.ConfigUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * 置空单聊页面菜单的“查找聊天记录”搜索结果
 */
class EmptySingChatHistoryGalleryPluginPart : IPlugin {
    val MediaHistoryGalleryUI = "com.tencent.mm.ui.chatting.gallery.MediaHistoryGalleryUI"
    var mChattingArguments: Bundle? = null

    override fun handleHook(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        handleImageQueryMainUI(context, lpparam)
        setEmptyDetailHistoryUI(context, lpparam)
        setEmptyActionBarTabPageUI(context, lpparam)
    }

    /**
     * 处理8.0.49之后出现的图片搜索页面，发现交谈者是要隐藏的用户，直接结束图片搜索页面。因为该页面是compose写的，没有可以下手的地方
     */
    private fun handleImageQueryMainUI(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        val ImageQueryMainUI = ClazzN.from("com.tencent.mm.view.activity.ImageQueryMainUI") ?: return
        XposedHelpers2.findAndHookMethod(
            ClazzN.from("com.tencent.mm.ui.chatting.BaseChattingUIFragment"),
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook2() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    mChattingArguments = XposedHelpers2.callMethod(param.thisObject, "getArguments")
                }
            })
        XposedHelpers2.findAndHookMethod(
            ClazzN.from("com.tencent.mm.ui.chatting.BaseChattingUIFragment"),
            "onDestroy",
            object : XC_MethodHook2() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    mChattingArguments = null
                }
            })

        XposedHelpers2.findAndHookMethod(
            ImageQueryMainUI,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook2() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val act: Activity = param.thisObject as Activity
                    if (mChattingArguments == null) {
                        return
                    }
                    val bundle: Bundle = mChattingArguments as Bundle
                    val thizUser = bundle.getString("Chat_User")
                    if (WXMaskPlugin.containChatUser(thizUser)) {
                        //隐藏。
                        act.finish()
                    }
                    val kSet = bundle.keySet()
                    val sb = StringBuilder()

                    for (key in kSet) {
                        sb.append(key + ": " + bundle.get(key) + ", ")
                    }
                    LogUtil.d("ImageQueryMainUI onCreate", sb.toString())
                }
            })

    }

    private fun setEmptyDetailHistoryUI(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        setEmptyDetailHistoryUIForMedia(context, lpparam)
        setEmptyDetailHistoryUIForGalleryCompat(context, lpparam)
    }

    private fun setEmptyDetailHistoryUIForMedia(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        var mediaMethodName = when (AppVersionUtil.getVersionCode()) {
            in Constrant.WX_CODE_8_0_32..Constrant.WX_CODE_8_0_35 -> "k"
            in Constrant.WX_CODE_8_0_35..Constrant.WX_CODE_8_0_43 -> "l"
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_44, Constrant.WX_CODE_PLAY_8_0_48 -> "z"
            in Constrant.WX_CODE_8_0_44..Constrant.WX_CODE_8_0_45 -> "A"
            Constrant.WX_CODE_8_0_47 -> "B"
            Constrant.WX_CODE_8_0_49, Constrant.WX_CODE_8_0_51, Constrant.WX_CODE_8_0_56 , Constrant.WX_CODE_8_0_58 -> "y"
            Constrant.WX_CODE_8_0_50 -> "K"
            Constrant.WX_CODE_8_0_53 -> "z"
            Constrant.WX_CODE_8_0_76 -> "A" // 8.0.76 MediaHistoryListUI.A(boolean, int)
            else -> "A"
        }
        val MediaHistoryListUI = "com.tencent.mm.ui.chatting.gallery.MediaHistoryListUI"
        var mediaMethod: Method? = XposedHelpers2.findMethodExactIfExists(
            MediaHistoryListUI,
            context.classLoader,
            mediaMethodName,
            java.lang.Boolean.TYPE,
            java.lang.Integer.TYPE
        )

        if (mediaMethod == null) {
            val guessMethods = XposedHelpers2.findMethodsByExactParameters(
                ClazzN.from(MediaHistoryListUI),
                Void.TYPE,
                java.lang.Boolean.TYPE,
                Integer.TYPE
            )
            if (guessMethods.size >= 1) {
                mediaMethod = guessMethods[0]
            }
            LogUtil.w(AppVersionUtil.getSmartVersionName(), "guess MediaHistoryListUI empty method is ", mediaMethod)
        }
        if (mediaMethod == null) {
            return
        }
        XposedHelpers2.hookMethod(mediaMethod, object : XC_MethodHook2() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!ConfigUtil.getOptionData().hideSingleSearch) {
                    return
                }
                val activity: Activity = param.thisObject as Activity
                val intent = activity.intent
                val userName = intent.getStringExtra("kintent_talker")
                if (userName.isNullOrBlank()) {
                    LogUtil.w("MediaHistoryListUI‘s user is empty", userName)
                    return
                }
                if (WXMaskPlugin.containChatUser(userName)) {
                    param.args[1] = 0
                    LogUtil.i("empty MediaHistoryListUI data")
                }
            }
        })
    }


    /**
     * 置空图片/视频搜索结果
     */
    private fun setEmptyDetailHistoryUIForGalleryCompat(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (AppVersionUtil.getVersionCode() > Constrant.WX_CODE_8_0_43) {
            setEmptyDetailHistoryUIForGallery8044(context, lpparam)
            return
        } else {
            setEmptyDetailHistoryUIForGallery(context, lpparam)
        }
    }

    private fun setEmptyDetailHistoryUIForGallery(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        val methodName = when (AppVersionUtil.getVersionCode()) {
            in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_35 -> "k"
            in Constrant.WX_CODE_8_0_35..Constrant.WX_CODE_8_0_43 -> "l"
            else -> null
        }
        var galleryMethod: Method? = null
        if (methodName != null) {
            galleryMethod = XposedHelpers2.findMethodExactIfExists(
                MediaHistoryGalleryUI,
                context.classLoader,
                methodName,
                java.lang.Boolean.TYPE,
                java.lang.Integer.TYPE,
            )
        }
        if (galleryMethod == null) {
            val guessMethods = XposedHelpers2.findMethodsByExactParameters(
                ClazzN.from(MediaHistoryGalleryUI),
                Void.TYPE,
                java.lang.Boolean.TYPE,
                Integer.TYPE,
            )
            if (guessMethods.isNotEmpty()) {
                galleryMethod = guessMethods[0]
            }
            LogUtil.w(AppVersionUtil.getSmartVersionName(), "guess MediaHistoryGalleryUI empty method is ", galleryMethod)
        }
        XposedHelpers2.hookMethod(
            galleryMethod,
            object : XC_MethodHook2() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ConfigUtil.getOptionData().hideSingleSearch) {
                        return
                    }
                    val activity: Activity = param.thisObject as Activity
                    val intent = activity.intent
                    val userName = intent.getStringExtra("kintent_talker")
                    if (userName.isNullOrBlank()) {
                        LogUtil.w("MediaHistoryListUI‘s user is empty", userName)
                        return
                    }
                    if (WXMaskPlugin.containChatUser(userName)) {
                        param.args[1] = 0
                        LogUtil.i("empty MediaHistoryGalleryUI data")
                    }
                }
            })
    }

    private fun setEmptyDetailHistoryUIForGallery8044(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        val presenterClazz = when (AppVersionUtil.getVersionCode()) {
            in Constrant.WX_CODE_8_0_44..Constrant.WX_CODE_8_0_53 -> "com.tencent.mm.ui.chatting.presenter.k1"
            Constrant.WX_CODE_8_0_76 -> "com.tencent.mm.ui.chatting.presenter.n3" // 8.0.76
            else -> "com.tencent.mm.ui.chatting.presenter.n3"
        }
        var methods = XposedHelpers2.findMethodsByExactParameters(
            ClazzN.from(presenterClazz),
            Void.TYPE,
            java.lang.Boolean.TYPE,
            Integer.TYPE,
        )
        if (methods?.isNotEmpty() == true) {
            XposedHelpers2.hookMethod(
                methods[0],
                object : XC_MethodHook2() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        super.beforeHookedMethod(param)
                        if (!ConfigUtil.getOptionData().hideSingleSearch) {
                            return
                        }

                        var fields = XposedHelpers2.findFieldsByExactPredicate(param.thisObject::class.java) {
                            var v = it.get(param.thisObject)

                            if (v != null && v.javaClass.name.equals(MediaHistoryGalleryUI)) {
                                return@findFieldsByExactPredicate true
                            }
                            return@findFieldsByExactPredicate false
                        }
                        var activity: Activity? = null
                        if (!fields.isEmpty()) {
                            activity = fields[0].get(param.thisObject) as? Activity
                        }
                        if (activity == null) {
                            // 尝试从 presenter 的成员变量获取 Context
                            try {
                                val ctx = XposedHelpers2.getObjectField<Context?>(param.thisObject, "f")
                                if (ctx is Activity) activity = ctx
                            } catch (e: Throwable) {
                            }
                        }
                        if (activity == null) {
                            LogUtil.w("can not find DetailHistoryUIForGallery8044")
                            return
                        }
                        val intent = activity.intent
                        val userName = intent.getStringExtra("kintent_talker")
                        if (userName.isNullOrBlank()) {
                            LogUtil.w("presenter‘s user is empty", userName)
                            return
                        }
                        if (WXMaskPlugin.containChatUser(userName)) {
                            param.args[1] = 0
                            LogUtil.i("empty MediaHistoryGalleryUI data (presenter)")
                        }
                    }
                }
            )
        }
    }

    private fun setEmptyActionBarTabPageUI(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        val commonHookMethodName = when (AppVersionUtil.getVersionCode()) {
            in Constrant.WX_CODE_8_0_32..Constrant.WX_CODE_8_0_43 -> "p"
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_44, Constrant.WX_CODE_PLAY_8_0_48 -> "t"
            in Constrant.WX_CODE_8_0_44..Constrant.WX_CODE_8_0_58 -> "s"
            else -> "s"
        }

        //tab==全部，搜索结果置空
        XposedHelpers2.findAndHookMethod(
            "com.tencent.mm.ui.chatting.search.multi.fragment.FTSMultiAllResultFragment",
            context.classLoader,
            commonHookMethodName,
            java.util.ArrayList::class.java,
            object : XC_MethodHook2() {

                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ConfigUtil.getOptionData().hideSingleSearch) {
                        return
                    }
                    debugLog(param)
                    if (isHitMaskId(param.thisObject)) {
                        val arrayList: java.util.ArrayList<*> = param.args[0] as java.util.ArrayList<*>
                        arrayList.clear()
                    }
                }
            }
        )
        if (commonHookMethodName == null) {
            LogUtil.i("setEmptyActionBarTabPageUI is null")
            return
        }
        //其他的/普通的/一般的tab，搜索结果置空
        XposedHelpers2.findAndHookMethod(
            "com.tencent.mm.ui.chatting.search.multi.fragment.FTSMultiNormalResultFragment",
            context.classLoader,
            commonHookMethodName,
            java.util.ArrayList::class.java,
            object : XC_MethodHook2() {

                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ConfigUtil.getOptionData().hideSingleSearch) {
                        return
                    }
                    debugLog(param)
                    if (isHitMaskId(param.thisObject)) {
                        val arrayList: java.util.ArrayList<*> = param.args[0] as java.util.ArrayList<*>
                        arrayList.clear()
                    }
                }
            }
        )

        // tab==图片，全体视图替换置空
        XposedHelpers2.findAndHookMethod(
            "com.tencent.mm.ui.chatting.search.multi.fragment.FTSMultiImageResultFragment",
            context.classLoader,
            "onCreateView",
            LayoutInflater::class.java,
            ViewGroup::class.java,
            Bundle::class.java,
            object : XC_MethodHook2() {

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ConfigUtil.getOptionData().hideSingleSearch) {
                        return
                    }
                    debugLog(param)
                    if (isHitMaskId(param.thisObject)) {
                        val inflater = param.args[0] as LayoutInflater
                        val viewGroup: ViewGroup = param.args[1] as ViewGroup
                        val layoutId = XposedHelpers2.callMethod<Int>(param.thisObject, "getLayoutId")
                        param.result = inflater.inflate(layoutId, viewGroup, false)
                    }
                }
            }
        )
    }

    private fun debugLog(param: XC_MethodHook.MethodHookParam) {
        LogUtil.d(
            "set empty for ${param.thisObject}",
            "hook method args:",
            param.args,
            "fragment arguments:",
            XposedHelpers2.callMethod(param.thisObject, "getArguments"),
        )
    }

    private fun isHitMaskId(fragmentObj: Any?): Boolean {
        val activity = XposedHelpers2.callMethod<Activity>(fragmentObj, "getActivity") as Activity?
        if (activity == null) {
            LogUtil.w("Not attach Activity for ", fragmentObj)
            return false
        }
        val intent = activity.intent
        LogUtil.d(activity, activity.intent.extras)

        val username = intent.getStringExtra("detail_username")
        return WXMaskPlugin.containChatUser(username)
    }
}