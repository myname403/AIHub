<script setup lang="ts">
import Motion from "./utils/motion";
import { useRouter } from "vue-router";
import { message } from "@/utils/message";
import { loginRules } from "./utils/rule";
import { ref, reactive, toRaw, onMounted } from "vue";
import { debounce } from "@pureadmin/utils";
import { useNav } from "@/layout/hooks/useNav";
import { useEventListener } from "@vueuse/core";
import type { FormInstance } from "element-plus";
import { useLayout } from "@/layout/hooks/useLayout";
import { useUserStoreHook } from "@/store/modules/user";
import { initRouter, getTopMenu } from "@/router/utils";
import { bg, avatar, illustration } from "./utils/static";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import { useDataThemeChange } from "@/layout/hooks/useDataThemeChange";

import dayIcon from "@/assets/svg/day.svg?component";
import darkIcon from "@/assets/svg/dark.svg?component";
import Lock from "~icons/ri/lock-fill";
import User from "~icons/ri/user-3-fill";
import OfficeBuilding from "~icons/ri/building-2-fill";
import ShieldKeyhole from "~icons/ri/shield-keyhole-fill";
import { http } from "@/utils/http";

defineOptions({
  name: "Login"
});

const router = useRouter();
const loading = ref(false);
const disabled = ref(false);
const ruleFormRef = ref<FormInstance>();

const { initStorage } = useLayout();
initStorage();

const { dataTheme, overallStyle, dataThemeChange } = useDataThemeChange();
dataThemeChange(overallStyle.value);
const { title } = useNav();

// AIHub 登录：租户码 + 用户名 + 密码 + 图形验证码（多租户 SaaS，租户码=企业标识）
const ruleForm = reactive({
  tenantCode: "demo",
  username: "admin",
  password: "123456",
  captchaId: "",
  captchaCode: ""
});

/** 图形验证码（防暴力破解；点击图片刷新，登录失败后也自动刷新） */
const captchaImg = ref("");
const loadCaptcha = async () => {
  try {
    const res: any = await http.request("get", "/auth/captcha");
    ruleForm.captchaId = res.id;
    ruleForm.captchaCode = "";
    captchaImg.value = res.imageBase64;
  } catch (e) {
    // 静默失败：验证码拉不到时不阻塞登录表单渲染（后端开关关闭时也走这里）
  }
};
onMounted(loadCaptcha);

const onLogin = async (formEl: FormInstance | undefined) => {
  if (!formEl) return;
  await formEl.validate(valid => {
    if (valid) {
      loading.value = true;
      useUserStoreHook()
        .loginByUsername({
          tenantCode: ruleForm.tenantCode,
          username: ruleForm.username,
          password: ruleForm.password,
          captchaId: ruleForm.captchaId,
          captchaCode: ruleForm.captchaCode
        })
        .then(res => {
          if (res.success) {
            // 获取后端路由
            return initRouter().then(() => {
              disabled.value = true;
              router
                .push(getTopMenu(true).path)
                .then(() => {
                  message("登录成功", { type: "success" });
                })
                .finally(() => (disabled.value = false));
            });
          } else {
            message("登录失败", { type: "error" });
            loadCaptcha();
          }
        })
        .catch(() => loadCaptcha())
        .finally(() => (loading.value = false));
    }
  });
};

const immediateDebounce: any = debounce(
  formRef => onLogin(formRef),
  1000,
  true
);

useEventListener(document, "keydown", ({ code }) => {
  if (
    ["Enter", "NumpadEnter"].includes(code) &&
    !disabled.value &&
    !loading.value
  )
    immediateDebounce(ruleFormRef.value);
});
</script>

<template>
  <div class="select-none">
    <img :src="bg" class="wave" />
    <div class="flex-c absolute right-5 top-3">
      <!-- 主题 -->
      <el-switch
        v-model="dataTheme"
        inline-prompt
        :active-icon="dayIcon"
        :inactive-icon="darkIcon"
        @change="dataThemeChange"
      />
    </div>
    <div class="login-container">
      <div class="img">
        <component :is="toRaw(illustration)" />
      </div>
      <div class="login-box">
        <div class="login-form">
          <avatar class="avatar" />
          <Motion>
            <h2 class="outline-hidden">{{ title }}</h2>
          </Motion>

          <el-form
            ref="ruleFormRef"
            :model="ruleForm"
            :rules="loginRules"
            size="large"
          >
            <Motion :delay="100">
              <el-form-item
                :rules="[
                  {
                    required: true,
                    message: '请输入租户码',
                    trigger: 'blur'
                  }
                ]"
                prop="tenantCode"
              >
                <el-input
                  v-model="ruleForm.tenantCode"
                  clearable
                  placeholder="租户码（企业标识）"
                  :prefix-icon="useRenderIcon(OfficeBuilding)"
                />
              </el-form-item>
            </Motion>

            <Motion :delay="150">
              <el-form-item
                :rules="[
                  {
                    required: true,
                    message: '请输入账号',
                    trigger: 'blur'
                  }
                ]"
                prop="username"
              >
                <el-input
                  v-model="ruleForm.username"
                  clearable
                  placeholder="账号"
                  :prefix-icon="useRenderIcon(User)"
                />
              </el-form-item>
            </Motion>

            <Motion :delay="150">
              <el-form-item prop="password">
                <el-input
                  v-model="ruleForm.password"
                  clearable
                  show-password
                  placeholder="密码"
                  :prefix-icon="useRenderIcon(Lock)"
                />
              </el-form-item>
            </Motion>

            <Motion :delay="200">
              <el-form-item prop="captchaCode">
                <div class="flex w-full gap-2">
                  <el-input
                    v-model="ruleForm.captchaCode"
                    clearable
                    placeholder="验证码"
                    :prefix-icon="useRenderIcon(ShieldKeyhole)"
                  />
                  <img
                    v-if="captchaImg"
                    :src="captchaImg"
                    title="点击刷新"
                    class="w-28! h-10! cursor-pointer rounded"
                    @click="loadCaptcha"
                  />
                </div>
              </el-form-item>
            </Motion>

            <Motion :delay="250">
              <el-button
                class="w-full mt-4!"
                size="default"
                type="primary"
                :loading="loading"
                :disabled="disabled"
                @click="onLogin(ruleFormRef)"
              >
                登录
              </el-button>
            </Motion>
          </el-form>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
@import url("@/style/login.css");
</style>

<style lang="scss" scoped>
:deep(.el-input-group__append, .el-input-group__prepend) {
  padding: 0;
}
</style>
