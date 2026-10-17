// ============================================================================
//  k6 秒杀压测脚本
//
//  为什么保留两个压测工具：
//   · scripts/BenchClient.java —— Java 21 虚拟线程，跑单机极限吞吐，结果最干净
//   · scripts/k6/seckill.js    —— k6 更适合分布式压测与 CI 门禁（阈值即失败）
//
//  用法：
//    k6 run -e BASE_URL=http://localhost:8080 -e ACTIVITY_ID=2001 scripts/k6/seckill.js
//
//  前提：应用以 benchmark profile 启动（关闭限流），且活动已预热。
// ============================================================================
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ACTIVITY = __ENV.ACTIVITY_ID || '2001';
const USERS = Number(__ENV.USERS || 200);
const VUS = Number(__ENV.VUS || 200);

export const options = {
  scenarios: {
    seckill: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5s', target: VUS },
        { duration: '20s', target: VUS },
        { duration: '5s', target: 0 },
      ],
    },
  },
  thresholds: {
    // 阈值不达标，k6 会以非 0 退出码结束——可以直接当 CI 门禁
    http_req_duration: ['p(95)<800'],
    http_req_failed: ['rate<0.02'],
  },
};

/** 预登录一批用户：一人一单要求每个请求用不同账号。 */
export function setup() {
  const tokens = [];
  for (let i = 1; i <= USERS; i++) {
    const res = http.post(
      `${BASE}/api/auth/login`,
      JSON.stringify({ username: `malluser${i}`, password: '123456' }),
      { headers: { 'Content-Type': 'application/json' } },
    );
    if (res.status === 200) {
      const body = res.json();
      if (body && body.data && body.data.token) {
        tokens.push(body.data.token);
      }
    }
  }
  console.log(`登录成功 ${tokens.length}/${USERS} 个账号`);
  return { tokens };
}

export default function (data) {
  if (!data.tokens.length) {
    return;
  }
  const token = data.tokens[(__VU + __ITER) % data.tokens.length];

  const res = http.post(`${BASE}/api/seckill/activities/${ACTIVITY}/order`, null, {
    headers: {
      Authorization: `Bearer ${token}`,
      // 每次迭代用不同设备号：压测的是"链路吞吐"，不是"风控规则"
      'X-Device-Id': `k6-${__VU}-${__ITER}`,
    },
    tags: { name: 'seckill-order' },
  });

  // 业务失败（售罄/重复）也是 HTTP 200，这里只校验协议层
  check(res, { 'HTTP 200': (r) => r.status === 200 });
  sleep(0.05);
}
