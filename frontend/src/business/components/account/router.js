/* eslint-disable */
export default {
  name: "Account",
  path: "/account",
  redirect: "/account/cloudaccount",
  components: {
    content: () => import(/* webpackChunkName: "setting" */ '@/business/components/account/base')
  },
  children: [
    {
      path: 'accountoverview',
      name: 'accountoverview',
      component: () => import(/* webpackChunkName: "api" */ '@/business/components/account/home/AccountOverview'),
    },
    {
      path: "cloudaccount",
      name: "cloudaccount",
      component: () => import(/* webpackChunkName: "api" */ '@/business/components/account/home/CloudAccount'),
    },
    {
      path: "accountscan/:id",
      name: "accountscan",
      component: () => import(/* webpackChunkName: "api" */ '@/business/components/account/home/AccountScan'),
    },
    {
      path: 'quartztask',
      name: 'quartztask',
      component: () => import(/* webpackChunkName: "api" */ '@/business/components/account/home/QuartzTask'),
    },
    {
      path: "clouddashboard",
      name: "cloudDashboard",
      component: () => import(/* webpackChunkName: "api" */ "@/business/components/account/home/Dashboard"),
    },
    {
      path: "history",
      name: "history",
      component: () => import(/* webpackChunkName: "api" */ "@/business/components/account/home/History"),
    },
  ]
}
