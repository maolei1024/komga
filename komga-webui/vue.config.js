// vue.config.js
const enableFullVueTypeCheck = process.env.KOMGA_FULL_VUE_TYPECHECK === 'true'

module.exports = {
  // with './' the dev server cannot load any arbitrary path
  // with '/' the prod build generates some url(/fonts…) calls in the css chunks, which doesn't work with a servlet context path
  publicPath: process.env.NODE_ENV === 'production' ? './' : '/',
  productionSourceMap: false,

  pluginOptions: {
    i18n: {
      locale: 'en',
      fallbackLocale: 'en',
      localeDir: 'locales',
      enableInSFC: false,
    },
  },

  devServer: {
    allowedHosts: 'all',
    client: {
      webSocketURL: 'ws://0.0.0.0:8081/ws',
    },
  },

  chainWebpack: config => {
    if (!enableFullVueTypeCheck) {
      // The legacy Vue 2 SFC checker grows beyond 4 GiB and aborts after several minutes.
      // `npm run build` runs the bounded TypeScript check before bundling instead.
      config.plugins.delete('fork-ts-checker')
    }
  },

  // custom rule for readium and r2d2bc css that needs to be made available, but untouched
  configureWebpack: {
    module: {
      rules: [
        {
          test: [
            /readium\/.*\.css.resource$/,
            /r2d2bc\/.*\.css.resource$/,
          ],
          type: 'asset/resource',
          generator: {
            filename: 'css/[hash].css[query]',
          },
        },
      ],
    },
  },
}
