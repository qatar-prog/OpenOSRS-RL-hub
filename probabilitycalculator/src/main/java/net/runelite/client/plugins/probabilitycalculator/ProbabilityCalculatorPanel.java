package net.runelite.client.plugins.probabilitycalculator;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

class ProbabilityCalculatorPanel extends PluginPanel
{
	ProbabilityCalculatorPanel(ProbabilityCalculatorInputArea inputArea, ProbabilityCalculatorOutputArea outputArea)
	{
		super();
		setBorder(new EmptyBorder(10, 10, 10, 10));
		setLayout(new GridBagLayout());

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		c.gridx = 0;
		c.gridy = 0;

		inputArea.setBorder(new EmptyBorder(12, 0, 12, 0));
		inputArea.setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(inputArea, c);
		c.gridy++;
		add(outputArea, c);
		c.gridy++;
	}
}