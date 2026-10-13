import { reactive } from "vue";
import type { FormRules } from "element-plus";

/**
 * 登录密码校验：只验证非空——登录环节做格式限制没有安全意义
 * （密码规则是"注册/改密"时的事），且 AIHub 后端密码为 6-64 位任意字符，
 * pure-admin 骨架默认的"8-18 位两种组合"规则会挡住合法演示账号。
 */
const loginRules = reactive<FormRules>({
  password: [
    {
      validator: (rule, value, callback) => {
        if (value === "") {
          callback(new Error("请输入密码"));
        } else {
          callback();
        }
      },
      trigger: "blur"
    }
  ]
});

export { loginRules };
