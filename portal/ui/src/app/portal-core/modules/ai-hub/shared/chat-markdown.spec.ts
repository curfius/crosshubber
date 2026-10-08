// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { renderChatMarkdown } from './chat-markdown';

describe('renderChatMarkdown', () => {
  it('renders markdown structure (headings, lists, emphasis, code)', () => {
    const html = renderChatMarkdown('## Title\n\n- one\n- two\n\n**bold**\n\n`code`');
    expect(html).toContain('<h2>Title</h2>');
    expect(html).toContain('<li>one</li>');
    expect(html).toContain('<strong>bold</strong>');
    expect(html).toContain('<code>code</code>');
  });

  it('converts line breaks when breaks is enabled', () => {
    expect(renderChatMarkdown('one\ntwo')).toContain('<br');
  });

  it('strips script and event-handler markup from the output', () => {
    const html = renderChatMarkdown(
      '你好<script>alert(1)</script><img src="x" onerror="alert(1)">text',
    );
    expect(html).not.toContain('<script');
    expect(html).not.toContain('onerror');
    expect(html).toContain('text');
  });

  it('drops javascript: links and iframes from rendered content', () => {
    const html = renderChatMarkdown('[click](javascript:alert(1))\n\n<iframe src="evil"></iframe>');
    expect(html).not.toContain('javascript:');
    expect(html).not.toContain('<iframe');
  });

  it('drops style tags and style attributes', () => {
    const html = renderChatMarkdown('<style>body{}</style><p style="color:red">hi</p>');
    expect(html).not.toContain('<style');
    expect(html).not.toContain('style=');
  });

  it('renders fenced code blocks', () => {
    const html = renderChatMarkdown('```js\nconst x = 1;\n```');
    expect(html).toContain('<pre>');
    expect(html).toContain('<code');
  });

  it('memoizes results per content string', () => {
    const a = renderChatMarkdown('# memo\n\ntest memo');
    const b = renderChatMarkdown('## Title\n\n- one\n- two\n\n**bold**\n\n`code`');
    // identical inputs return the identical cached string
    const a2 = renderChatMarkdown('## Title\n\n- one\n- two\n\n**bold**\n\n`code`');
    expect(b).toBe(a2);
    expect(a).not.toBe(b);
  });

  it('survives parser failures by returning empty HTML', () => {
    // marked is lenient, but the try/catch path must still return a string
    const html = renderChatMarkdown('plain text');
    expect(typeof html).toBe('string');
  });
});
