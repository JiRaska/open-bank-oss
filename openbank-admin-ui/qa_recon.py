from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    print("Navigating to Product Catalog...")
    page.goto('http://localhost:3000/product-catalog')
    page.wait_for_load_state('networkidle')
    
    print("Page title:", page.title())
    
    print("Capturing DOM for buttons and modals...")
    buttons = page.locator('button').all_inner_texts()
    print("Buttons found:", buttons)
    
    html = page.content()
    with open('dom_dump.html', 'w') as f:
        f.write(html)
        
    page.screenshot(path='recon.png', full_page=True)
    browser.close()
