FROM node:22-alpine

WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund && npm cache clean --force
COPY history-parser.mjs server.mjs ./

ENV NODE_ENV=production PORT=8787 DATA_DIR=/app/data HISTORY_LIMIT=18 MAX_HISTORY_PAGES=2 CACHE_SECONDS=75
EXPOSE 8787
USER node
CMD ["node", "server.mjs"]
