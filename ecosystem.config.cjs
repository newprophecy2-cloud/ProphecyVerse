module.exports = {
  apps: [
    {
      name: "prophecyverse",
      script: "dist/server.cjs",
      instances: 1,
      exec_mode: "fork",
      autorestart: true,
      watch: false,
      max_memory_restart: "512M",
      kill_timeout: 5000,
      env: {
        NODE_ENV: "production",
        PORT: 3000,
      },
    },
  ],
};
