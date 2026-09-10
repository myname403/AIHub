-- 容器首次启动时执行：创建两个服务的独立库（服务间禁止跨库 JOIN）
CREATE DATABASE IF NOT EXISTS aihub_platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS aihub_ai       DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
