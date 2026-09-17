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
 * 8.0.76 极简纯净方案：
 * 绝不对主页 View 树做任何 setVisibility 篡改！
 * 避免破坏 NoMeasuredTextView 与 unread tip TextView 的布局尺寸与 View 复用机制。
 * 纯粹在数据源层（fh5.w0.f(int) / getItem(int)）将私密好友的 content、digest 清空为 ""，
 * 将 unReadCount、UnReadInvite 等未读数置为 0，并将 msgType 标注为普通文本 "1"。
 * 
 * 只有当用户显式开启“主页变脸（enableMapConversation）”时，才进行 username 映射。
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