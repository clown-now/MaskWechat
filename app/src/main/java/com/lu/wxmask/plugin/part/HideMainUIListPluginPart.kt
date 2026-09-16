package com.lu.wxmask.plugin.part

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.TextView
import com.lu.lposed.api2.XC_MethodHook2
import com.lu.lposed.api2.XposedHelpers2
import com.lu.lposed.plugin.IPlugin
import com.lu.magic.util.ResUtil
import com.lu.wxmask.util.ext.getViewId
import com.lu.magic.util.log.LogUtil
import com.lu.wxmask.ClazzN
import com.lu.wxmask.Constrant
import com.lu.wxmask.MainHook
import com.lu.wxmask.plugin.WXMaskPlugin
import com.lu.wxmask.util.AppVersionUtil
import com.lu.wxmask.util.ConfigUtil
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 主页UI（微信Tab消息列表）处理插件
 * 
 * 8.0.76 架构与精准过滤：
 * 1. 适配器类：fh5.w0
 * 2. 避免误伤：严禁在未获取到明确用户名时做兜底隐藏！
 *    通过 param.args[0] (position) -> adapter.f(pos) / getItem(pos) 精准获取对应的 k4 / Conversation
 *    从中取出 field_username，必须精确命中 WXMaskPlugin.containChatUser(chatUser) 才执行隐藏。
 * 3. 彻底抹除：
 *    对命中配置的用户，清除 NoMeasuredTextView (fh5.n.f) 的文本和 View.INVISIBLE，
 *    隐藏红点 (fh5.n.g) 和草稿提示 (fh5.n.e)。
 */
class HideMainUIListPluginPart : IPlugin {

    val GetItemMethodName = when (AppVersionUtil.getVersionCode()) {
        Constrant.WX_CODE_8_0_22 -> "aCW"
        in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_43 -> "k"
        Constrant.WX_CODE_PLAY_8_0_48 -> "l"
        Constrant.WX_CODE_8_0_49, Constrant.WX_CODE_8_0_51, Constrant.WX_CODE_8_0_56 -> "l"
        Constrant.WX_CODE_8_0_50 -> "n"
        Constrant.WX_CODE_8_0_53 -> "m"
        Constrant.WX_CODE_8_0_58 -> "m"
        Constrant.WX_CODE_8_0_60 -> "m"
        Constrant.WX_CODE_8_0_76 -> "f" // 8.0.76
        else -> "f"
    }

    override fun handleHook(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            handleMainUIChattingListView2(context, lpparam)
        }.onFailure {
            LogUtil.w("hide mainUI listview2 fail, try to fallback ListView hook.", it)
            handleMainUIChattingListView(context, lpparam)
        }
    }

    private fun handleMainUIChattingListView2(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val adapterClazzName = when (AppVersionUtil.getVersionCode()) {
            Constrant.WX_CODE_8_0_22 -> "com.tencent.mm.ui.g"
            in Constrant.WX_CODE_8_0_32..Constrant.WX_CODE_8_0_34 -> "com.tencent.mm.ui.y"
            in Constrant.WX_CODE_8_0_35..Constrant.WX_CODE_8_0_38 -> "com.tencent.mm.ui.z"
            in Constrant.WX_CODE_8_0_40..Constrant.WX_CODE_8_0_43 -> "com.tencent.mm.ui.b0"
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_44 -> "com.tencent.mm.ui.h3"
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_47,
            Constrant.WX_CODE_PLAY_8_0_48, Constrant.WX_CODE_8_0_50, Constrant.WX_CODE_8_0_51, Constrant.WX_CODE_8_0_53, Constrant.WX_CODE_8_0_56 -> "com.tencent.mm.ui.i3"
            in Constrant.WX_CODE_8_0_58..Constrant.WX_CODE_8_0_60 -> "com.tencent.mm.ui.k3"
            Constrant.WX_CODE_8_0_76 -> "fh5.w0"
            else -> null
        }

        var getItemMethod: Method? = null
        if (adapterClazzName != null) {
            val adapterClass = ClazzN.from(adapterClazzName, context.classLoader)
            getItemMethod = findGetItemMethod(adapterClass)
            hookAdapterGetView(adapterClass)
        }

        if (getItemMethod != null) {
            LogUtil.i("Found exact getItem method for WeChat ${AppVersionUtil.getSmartVersionName()}: $getItemMethod")
            hookListViewGetItem(getItemMethod)
            return
        }

        LogUtil.w("WeChat MainUI ListView not found adapter by name, starting dynamic setAdapter hook.")
        handleMainUIChattingListView(context, lpparam)
    }

    private fun handleMainUIChattingListView(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers2.findAndHookMethod(
            ListView::class.java,
            "setAdapter",
            ListAdapter::class.java,
            object : XC_MethodHook2() {
                private var isHooked = false

                override fun afterHookedMethod(param: MethodHookParam) {
                    val adapter = param.args[0] ?: return
                    val adapterClassName = adapter.javaClass.name
                    LogUtil.d("MainUI setAdapter: $adapterClassName")

                    if (isHooked) return

                    if (adapterClassName.startsWith("com.tencent.mm.ui.conversation") || adapterClassName == "fh5.w0") {
                        hookAdapterGetView(adapter.javaClass)
                        var m = findGetItemMethod(adapter.javaClass)
                        if (m == null && adapter.javaClass.superclass != null) {
                            m = findGetItemMethod(adapter.javaClass.superclass)
                        }
                        if (m == null) {
                            m = XposedHelpers2.findMethodExactIfExists(adapter.javaClass, "getItem", Integer.TYPE)
                        }
                        if (m != null) {
                            LogUtil.i("Dynamic hook getItem method succeeded: $m")
                            hookListViewGetItem(m)
                            isHooked = true
                        }
                    }
                }
            }
        )
    }

    private fun hookAdapterGetView(adapterClazz: Class<*>?) {
        if (adapterClazz == null) return
        val getViewMethod = XposedHelpers2.findMethodExactIfExists(
            adapterClazz,
            "getView",
            Integer.TYPE,
            View::class.java,
            ViewGroup::class.java
        ) ?: return

        val methodSign = getViewMethod.toString()
        if (MainHook.uniqueMetaStore.contains(methodSign)) {
            return
        }

        XposedHelpers2.hookMethod(
            getViewMethod,
            object : XC_MethodHook2() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val itemView = param.result as? View ?: return
                    val pos = param.args[0] as? Int ?: return

                    // 精准获取当前 position 的用户名，绝不模糊推断
                    var chatUser: String? = null

                    // 途径 1：调用 Adapter 的获取数据方法
                    try {
                        val item = XposedHelpers2.callMethod<Any?>(param.thisObject, GetItemMethodName, pos)
                            ?: XposedHelpers2.callMethod<Any?>(param.thisObject, "getItem", pos)
                        if (item != null) {
                            chatUser = try {
                                XposedHelpers2.getObjectField<String?>(item, "field_username")
                            } catch (e: Throwable) {
                                null
                            }
                        }
                    } catch (e: Throwable) {
                    }

                    // 途径 2：从 ViewHolder (tag) 中提取底层实体 (d 字段)
                    if (chatUser.isNullOrEmpty()) {
                        val tag = itemView.tag
                        if (tag != null) {
                            try {
                                val conv = XposedHelpers2.getObjectField<Any?>(tag, "d")
                                if (conv != null) {
                                    chatUser = XposedHelpers2.getObjectField<String?>(conv, "field_username")
                                }
                            } catch (e: Throwable) {
                            }
                        }
                    }

                    // 只有在明确拿到 chatUser 且命中黑名单时才处理，避免误杀未配置的用户！
                    if (!chatUser.isNullOrEmpty() && WXMaskPlugin.containChatUser(chatUser)) {
                        maskItemViews(itemView, itemView.tag)
                    }
                }
            }
        )
        MainHook.uniqueMetaStore.add(methodSign)
        LogUtil.i("Successfully hooked getView method: $methodSign")
    }

    private fun maskItemViews(itemView: View, tag: Any?) {
        try {
            if (tag != null) {
                // 抹除最后一条消息 NoMeasuredTextView (fh5.n.f)
                try {
                    val lastMsgView = XposedHelpers2.getObjectField<Any?>(tag, "f")
                    if (lastMsgView != null) {
                        XposedHelpers2.callMethod<Any?>(lastMsgView, "setText", "")
                        (lastMsgView as? View)?.visibility = View.INVISIBLE
                    }
                } catch (e: Throwable) {
                }

                // 抹除草稿/状态 TextView (fh5.n.e)
                try {
                    val statusTv = XposedHelpers2.getObjectField<TextView?>(tag, "e")
                    statusTv?.text = ""
                    statusTv?.visibility = View.INVISIBLE
                } catch (e: Throwable) {
                }

                // 抹除红点 TextView (fh5.n.g)
                try {
                    val tipTv = XposedHelpers2.getObjectField<TextView?>(tag, "g")
                    tipTv?.visibility = View.INVISIBLE
                } catch (e: Throwable) {
                }
            }

            // 补充资源 ID 级隐藏
            val tipTvId = ResUtil.getViewId("kmv")
            if (tipTvId != 0) {
                itemView.findViewById<View>(tipTvId)?.visibility = View.INVISIBLE
            }
            val lastMsgId = ResUtil.getViewId("ht5")
            if (lastMsgId != 0) {
                itemView.findViewById<View>(lastMsgId)?.visibility = View.INVISIBLE
            }
        } catch (e: Throwable) {
            LogUtil.w("maskItemViews error: ", e)
        }
    }

    private fun findGetItemMethod(adapterClazz: Class<*>?): Method? {
        if (adapterClazz == null) return null

        var method: Method? = XposedHelpers2.findMethodExactIfExists(adapterClazz, GetItemMethodName, Integer.TYPE)
        if (method != null) return method

        method = XposedHelpers2.findMethodExactIfExists(adapterClazz, "getItem", Integer.TYPE)
        if (method != null) return method

        val methods = XposedHelpers2.findMethodsByExactPredicate(adapterClazz) { m ->
            val isPrimitiveOrVoid = arrayOf(
                Object::class.java,
                String::class.java,
                Byte::class.java,
                Short::class.java,
                Long::class.java,
                Float::class.java,
                Double::class.java,
                java.lang.Byte.TYPE,
                java.lang.Short.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Long.TYPE,
                java.lang.Float.TYPE,
                java.lang.Double.TYPE,
                java.lang.Void.TYPE
            ).contains(m.returnType)

            val paramValid = m.parameterTypes.size == 1 && m.parameterTypes[0] == Integer.TYPE
            return@findMethodsByExactPredicate paramValid && !isPrimitiveOrVoid && Modifier.isPublic(m.modifiers) && !Modifier.isAbstract(m.modifiers)
        }

        if (methods.isNotEmpty()) {
            method = methods[0]
            LogUtil.d("Found getItem method by heuristic predicate: $method")
        }
        return method
    }

    private fun hookListViewGetItem(getItemMethod: Method) {
        val methodSign = getItemMethod.toString()
        if (MainHook.uniqueMetaStore.contains(methodSign)) {
            return
        }

        XposedHelpers2.hookMethod(
            getItemMethod,
            object : XC_MethodHook2() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val itemData: Any = param.result ?: return

                    val chatUser: String? = try {
                        XposedHelpers2.getObjectField(itemData, "field_username")
                    } catch (e: Throwable) {
                        null
                    }

                    if (chatUser.isNullOrEmpty()) {
                        return
                    }

                    if (WXMaskPlugin.containChatUser(chatUser)) {
                        val option = ConfigUtil.getOptionData()

                        if (option.enableMapConversation) {
                            WXMaskPlugin.getMaskBeamById(chatUser)?.let {
                                try {
                                    XposedHelpers2.setObjectField(itemData, "field_username", it.mapId)
                                } catch (e: Throwable) {
                                    LogUtil.w("Map conversation error: ", e)
                                }
                            }
                        }

                        try {
                            XposedHelpers2.setObjectField(itemData, "field_content", "")
                            XposedHelpers2.setObjectField(itemData, "field_digest", "")
                            XposedHelpers2.setObjectField(itemData, "field_unReadCount", 0)
                            XposedHelpers2.setObjectField(itemData, "field_UnReadInvite", 0)
                            XposedHelpers2.setObjectField(itemData, "field_unReadMuteCount", 0)
                            XposedHelpers2.setObjectField(itemData, "field_msgType", "1")
                        } catch (e: Throwable) {
                            LogUtil.w("Mask fields error: ", e)
                        }

                        if (option.enableTravelTime && option.travelTime != 0L) {
                            try {
                                val cTime = XposedHelpers2.getObjectField<Any>(itemData, "field_conversationTime")
                                if (cTime is Long) {
                                    XposedHelpers2.setObjectField(itemData, "field_conversationTime", cTime - option.travelTime)
                                }
                            } catch (e: Throwable) {
                                LogUtil.w("Travel time error: ", e)
                            }
                        }
                    }
                }
            }
        )
        MainHook.uniqueMetaStore.add(methodSign)
        LogUtil.i("Successfully hooked getItem method: $methodSign")
    }
}