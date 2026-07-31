(function () {
  const themes = [
    {
      id: "aerial",
      name: "A. 空灵玻璃",
      note: "轻量玻璃感，适合强调总览和现代运维感。",
      colors: ["#2376ea", "#6ecbff", "#2ec2b0", "#ffffff"]
    },
    {
      id: "anchor",
      name: "B. 稳态主控",
      note: "更稳、更像工业主控台，强调边界和秩序。",
      colors: ["#145ebf", "#264f78", "#dce7f3", "#ffffff"]
    },
    {
      id: "pulse",
      name: "C. 信号脉冲",
      note: "提高状态对比，适合需要更强告警感知的场景。",
      colors: ["#ff8f4a", "#ffcf87", "#1ec7aa", "#17344f"]
    },
    {
      id: "forge",
      name: "D. 工业锻造",
      note: "更硬朗的面板感和更少圆角，偏传统 SCADA 气质。",
      colors: ["#1b2f44", "#5d7690", "#d4dde8", "#ffffff"]
    },
    {
      id: "beacon",
      name: "E. 蓝绿信标",
      note: "蓝绿监控向，强调实时数据和诊断感。",
      colors: ["#16b8ad", "#71ddd3", "#154765", "#ffffff"]
    }
  ];

  function currentThemeId() {
    return localStorage.getItem("collectorDesignTheme") || "anchor";
  }

  function applyTheme(themeId) {
    const nextTheme = themes.find((item) => item.id === themeId) || themes[1];
    document.body.classList.remove(...themes.map((item) => `theme-${item.id}`));
    document.body.classList.add(`theme-${nextTheme.id}`);
    localStorage.setItem("collectorDesignTheme", nextTheme.id);
  }

  window.__collectorDesignLab = {
    themes,
    applyTheme,
    currentThemeId
  };
})();
