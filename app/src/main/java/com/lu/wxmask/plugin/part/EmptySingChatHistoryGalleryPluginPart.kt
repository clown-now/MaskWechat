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
 * 
 * 8.0.76 强力方案：
 * 只要检测到查看的是私密用户的“查找聊天记录”（无论是图片及视频、表情、文件还是链接）：
 * 在 Activity 启动时如果命中私密好友，直接关闭或清空数据源，彻底不给暴露任何记录的机会！
 */
class EmptySingChatHistoryGalleryPluginPart : IPlugin {
    val MediaHistoryGalleryUI = "com.tencent.mm.ui.chatting.gallery.MediaHistoryGalleryUI"
    val MediaHistoryListUI = "com.tencent.mm.ui.chatting.gallery.MediaHistoryListUI"
    var mChattingArguments: Bundle? = null

    override fun handleHook(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        handleImageQueryMainUI(context, lpparam)
        setEmptyDetailHistoryUI(context, lpparam)
        setEmptyActionBarTabPageUI(context, lpparam)
    }

    /**
     * 处理8.0.49之后出现的图片搜索页面，发现交谈者是要隐藏的用户，直接结束图片搜索页面。
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
                        act.finish()
                    }
                }
            })
    }

    private fun setEmptyDetailHistoryUI(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        // 1. Hook MediaHistoryGalleryUI（图片及视频历史入口）
        // 兜底直接在 onCreate 时拦截！如果私密好友进入，直接 finish，绝对不给图片视频显示的机会！
        XposedHelpers2.findAndHookMethod(
            MediaHistoryGalleryUI,
            context.classLoader,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook2() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ConfigUtil.getOptionData().hideSingleSearch) {
                        return
                    }
                    val activity = param.thisObject as? Activity ?: return
                    val userName = activity.intent?.getStringExtra("kintent_talker")
                    if (!userName.isNullOrBlank() && WXMaskPlugin.containChatUser(userName)) {
                        LogUtil.i("MediaHistoryGalleryUI finish for masked user: $userName")
                        activity.finish()
                    }
                }
            }
        )

        // 2. Hook MediaHistoryListUI（表情、文件、链接入口）
        // 同样在 onCreate 时进行检查
        XposedHelpers2.findAndHookMethod(
            MediaHistoryListUI,
            context.classLoader,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook2() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ConfigUtil.getOptionData().hideSingleSearch) {
                        return
                    }
                    val activity = param.thisObject as? Activity ?: return
                    val userName = activity.intent?.getStringExtra("kintent_talker")
                    if (!userName.isNullOrBlank() && WXMaskPlugin.containChatUser(userName)) {
                        LogUtil.i("MediaHistoryListUI finish for masked user: $userName")
                        activity.finish()
                    }
                }
            }
        )

        // 数据置空逻辑
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
            Constrant.WX_CODE_8_0_76 -> "A"
            else -> "A"
        }
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
                    return
                }
                if (WXMaskPlugin.containChatUser(userName)) {
                    param.args[1] = 0
                    LogUtil.i("empty MediaHistoryListUI data")
                }
            }
        })
    }

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
            Constrant.WX_CODE_8_0_76 -> "com.tencent.mm.ui.chatting.presenter.n3"
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
                            try {
                                val ctx = XposedHelpers2.getObjectField<Context?>(param.thisObject, "f")
                                if (ctx is Activity) activity = ctx
                            } catch (e: Throwable) {
                            }
                        }
                        if (activity == null) {
                            return
                        }
                        val intent = activity.intent
                        val userName = intent.getStringExtra("kintent_talker")
                        if (userName.isNullOrBlank()) {
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

        // tab==全部
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
                    if (isHitMaskId(param.thisObject)) {
                        val arrayList: java.util.ArrayList<*> = param.args[0] as java.util.ArrayList<*>
                        arrayList.clear()
                    }
                }
            }
        )

        // tab==普通
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
                    if (isHitMaskId(param.thisObject)) {
                        val arrayList: java.util.ArrayList<*> = param.args[0] as java.util.ArrayList<*>
                        arrayList.clear()
                    }
                }
            }
        )

        // tab==图片
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

    private fun isHitMaskId(fragmentObj: Any?): Boolean {
        val activity = XposedHelpers2.callMethod<Activity>(fragmentObj, "getActivity") as Activity?
        if (activity == null) {
            return false
        }
        val intent = activity.intent
        val username = intent.getStringExtra("detail_username")
        return WXMaskPlugin.containChatUser(username)
    }
}