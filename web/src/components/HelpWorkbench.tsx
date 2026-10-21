import { Button } from 'antd';
import { GETTING_STARTED_STEPS, HELP_TOPICS } from '../onboarding/helpContent.ts';

export function HelpWorkbench({
  onRestartOnboarding,
  onOpenHash,
}: {
  onRestartOnboarding: () => void;
  onOpenHash: (hash: string) => void;
}) {
  return (
    <section className="help-page" aria-label="帮助">
      <header className="help-header">
        <div>
          <h1>帮助与上手</h1>
          <p>小企业进销存、生产和财务可以先按三步把账建起来，再按模块日常操作。</p>
        </div>
        <Button type="primary" onClick={onRestartOnboarding}>打开新手引导</Button>
      </header>

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
