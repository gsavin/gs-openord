package org.graphstream.algorithm.layout.openord;

import org.graphstream.algorithm.generator.BarabasiAlbertGenerator;
import org.graphstream.graph.Graph;
import org.graphstream.graph.implementations.AdjacencyListGraph;

public class Demo {
	public static void main(String... args) {
		Graph g = new AdjacencyListGraph("g");
		BarabasiAlbertGenerator gen = new BarabasiAlbertGenerator();

		gen.addSink(g);
		gen.begin();
		for (int i = 0; i < 10000; i++)
			gen.nextEvents();
		gen.end();

		OpenOrdLayout layout = new OpenOrdLayout();
		layout.init(g);
		layout.compute();
		
		g.addAttribute("ui.quality");
		g.addAttribute("ui.antialias");
		g.display(false);
	}
}