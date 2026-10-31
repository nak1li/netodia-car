FROM node:20-slim
WORKDIR /app

# 注意顺序：postinstall 会执行 tools/patch_netease_api.js，
# 所以 tools/ 必须在 npm install 之前就位，否则构建直接失败。
COPY server/package.json /app/package.json
COPY server/tools/ /app/tools/
RUN npm install --omit=dev --no-audit --no-fund

COPY server/ /app/server/

ENV NCM_PORT=28475 \
    NCM_CACHE_DIR=/cache/audio \
    NCM_META_DIR=/cache/meta \
    NC_COOKIE_FILE=/secrets/ncm.cookie
EXPOSE 28475
VOLUME ["/cache", "/secrets"]
CMD ["node", "/app/server/server.js"]
