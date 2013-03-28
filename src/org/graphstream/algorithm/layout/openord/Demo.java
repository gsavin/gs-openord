package org.graphstream.algorithm.layout.openord;

import java.util.Scanner;

import org.graphstream.algorithm.generator.BarabasiAlbertGenerator;
import org.graphstream.graph.Graph;
import org.graphstream.graph.implementations.AdjacencyListGraph;

public class Demo {
	public static void main(String... args) {
		int count = 1000;

		if (args.length > 0)
			count = Integer.parseInt(args[0]);

		Graph g = new AdjacencyListGraph("g");
		BarabasiAlbertGenerator gen = new BarabasiAlbertGenerator();

		gen.addSink(g);
		gen.begin();
		for (int i = 0; i < count; i++)
			gen.nextEvents();
		gen.end();

		OpenOrdLayout layout = new OpenOrdLayout();
		layout.init(g);
		layout.compute();

		g.addAttribute("ui.quality");
		g.addAttribute("ui.antialias");
		g.display(false);

		Scanner s = new Scanner(System.in);
		System.out.printf("<< press any key to exit main() >>\n");
		s.nextLine();
	}
}
