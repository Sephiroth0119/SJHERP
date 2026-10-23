import { useEffect, useState } from 'react';
import { Button } from 'antd';
import { GETTING_STARTED_STEPS, HELP_TOPICS } from '../onboarding/helpContent.ts';
import {
  fetchLicenseStatus,
  licenseStatusTone,
  type LicenseStatus,
} from '../api/licenseApi.ts';
import { ApiError } from '../api/http.ts';

export function HelpWorkbench({
  onRestartOnboarding,
  onOpenHash,
  canViewLicense,
}: {
  onRestartOnboarding: () => void;
  onOpenHash: (hash: string) => void;
  canViewLicense: boolean;
}) {
  const [license, setLicense] = useState<LicenseStatus | null>(null);
  const [licenseError, setLicenseError] = useState<string | null>(null);

  useEffect(() => {
    if (!canViewLicense) {
      setLicense(null);
      setLicenseError(null);
      return;
    }
    let cancelled = false;
    fetchLicenseStatus()
      .then((status) => {
        if (!cancelled) {
          setLicense(status);
          setLicenseError(null);
        }
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        setLicense(null);
        setLicenseError(error instanceof ApiError ? error.message : '无法读取许可状态');
      });
    return () => {
      cancelled = true;
    };
  }, [canViewLicense]);

  return (
    <section className="help-page" aria-label="帮助">
      <header className="help-header">
        <div>
          <h1>帮助与上手</h1>
          <p>小企业进销存、生产和财务可以先按三步把账建起来，再按模块日常操作。</p>
        </div>
        <Button type="primary" onClick={onRestartOnboarding}>打开新手引导</Button>
      </header>

      {canViewLicense ? (
        <section className="help-panel" aria-label="许可状态">
          <h2>许可状态</h2>
          {licenseError ? <p>{licenseError}</p> : null}
          {!licenseError && !license ? <p>正在读取许可状态…</p> : null}
          {license ? (
            <>
              <p className={`license-status license-status-${licenseStatusTone(license.status)}`}>
                {license.statusLabel}
                {license.expiresAt ? ` · 到期 ${license.expiresAt}` : ''}
                {!license.configured ? ' · 未放置 license 文件' : ''}
              </p>
              {license.subject ? <p>签发对象：{license.subject}</p> : null}
              {license.graceUntil && license.status === 'GRACE' ? <p>宽限至 {license.graceUntil}</p> : null}
              {license.blocking ? <p>硬阻断已开启，业务接口已暂停。把有效 license 放到约定路径或关闭 SJHERP_LICENSE_ENFORCE 后重试。</p> : null}
              <p>{license.message}</p>
              <p>这是私有部署的离线许可校验，没有在线支付或订阅入口。商业模式未定。</p>
            </>
          ) : null}
        </section>
      ) : null}

      <section className="help-panel">
        <h2>第一次怎么走</h2>
        <ol className="onboarding-list">
          {GETTING_STARTED_STEPS.map((item) => <li key={item}>{item}</li>)}
        </ol>
        <p>向导只调用现有档案、期初和采购订单接口，不会写入演示假数据。完整说明见仓库 <code>docs/用户-上手指南.md</code>。</p>
      </section>

      <div className="help-grid">
        {HELP_TOPICS.map((topic) => (
          <article key={topic.key} className="help-card">
            <h2>{topic.title}</h2>
            <p>{topic.summary}</p>
            <ol>
              {topic.steps.map((step) => <li key={step}>{step}</li>)}
            </ol>
            <Button type="link" onClick={() => onOpenHash(topic.hash)}>打开对应工作台</Button>
          </article>
        ))}
      </div>
    </section>
  );
}
