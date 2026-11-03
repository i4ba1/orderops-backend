INSERT INTO inventory_items(sku, name, available_quantity)
VALUES ('COFFEE', 'Coffee beans', 10), ('TEA', 'Green tea', 5), ('MUG', 'Ceramic mug', 1)
ON CONFLICT (sku) DO NOTHING;
